package com.tripmate.identity.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.Role;
import com.tripmate.identity.entity.SessionRevocationReason;
import com.tripmate.identity.entity.TotpBackupCode;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.mail.TwoFactorNoticeEvent;
import com.tripmate.identity.repository.TotpBackupCodeRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.platform.web.ClientAddress;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

/**
 * Turning the caller's second factor on and off, and replacing backup codes.
 * The user ID always comes from the verified access token.
 *
 * <p>Every change re-authenticates: setup needs the password, so a session
 * left open on a shared computer cannot bind someone else's phone to the
 * account; disable and regenerate need the password and a current code, so a
 * stolen access token alone can neither switch 2FA off nor mint backup codes.
 * Passwords are checked before any transaction opens, since Argon2id must not
 * hold a database connection; the state is then re-read under the user row
 * lock and the code checked there, where it is also spent.
 */
@Service
@EnableConfigurationProperties({TwoFactorProperties.class, AuthRateLimitProperties.class})
public class TwoFactorService {

    static final String ACCOUNT_BUCKET = "two-factor-account";

    private final UserRepository users;

    private final TotpBackupCodeRepository backupCodes;

    private final BackupCodeHasher backupCodeHasher;

    private final SecondFactorVerifier verifier;

    private final FieldEncryption encryption;

    private final SessionService sessions;

    private final PasswordEncoder passwordEncoder;

    private final RateLimiter rateLimiter;

    private final AuthRateLimitProperties limits;

    private final TwoFactorProperties properties;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public TwoFactorService(UserRepository users, TotpBackupCodeRepository backupCodes,
                            BackupCodeHasher backupCodeHasher, SecondFactorVerifier verifier,
                            FieldEncryption encryption, SessionService sessions, PasswordEncoder passwordEncoder,
                            RateLimiter rateLimiter, AuthRateLimitProperties limits, TwoFactorProperties properties,
                            ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.users = users;
        this.backupCodes = backupCodes;
        this.backupCodeHasher = backupCodeHasher;
        this.verifier = verifier;
        this.encryption = encryption;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.properties = properties;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public TwoFactorStatus status(SessionPrincipal caller) {
        return transactions.execute(status -> {
            User user = activeUser(caller);
            return new TwoFactorStatus(user.isTotpEnabled(), user.hasPendingTotpEnrolment(),
                    user.isTotpEnabled() ? verifier.remainingBackupCodes(user) : 0);
        });
    }

    /**
     * Stores a new secret, encrypted, as pending, and returns it once in
     * plain form. A second call before confirming replaces the first secret.
     */
    public TwoFactorSetup setup(SessionPrincipal caller, String password) {
        consumeAttempt(caller);
        requirePassword(caller, password);
        return transactions.execute(status -> {
            User user = lockedActiveUser(caller);
            if (user.isTotpEnabled()) {
                throw new ApiProblemException(TwoFactorProblem.ALREADY_ENABLED);
            }
            byte[] secret = Totp.generateSecret();
            try {
                user.startTotpEnrolment(encryption.encrypt(user, EncryptedField.TOTP_SECRET, secret), clock.instant());
                String encoded = Base32.encode(secret);
                return new TwoFactorSetup(encoded, otpauthUri(user.getEmail(), encoded), properties.issuer(),
                        user.getEmail());
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        });
    }

    /**
     * Turns 2FA on with the first code from the pending secret, and returns
     * the backup codes - the only time they exist outside a hash. Every other
     * session is ended: they were opened on a password alone.
     */
    public List<String> confirm(SessionPrincipal caller, String code, ClientAddress client) {
        consumeAttempt(caller);
        return transactions.execute(status -> {
            User user = lockedActiveUser(caller);
            if (user.isTotpEnabled()) {
                throw new ApiProblemException(TwoFactorProblem.ALREADY_ENABLED);
            }
            if (!user.hasPendingTotpEnrolment()) {
                throw new ApiProblemException(TwoFactorProblem.NO_PENDING_SETUP);
            }
            Instant now = clock.instant();
            OptionalLong step = verifier.verifyPendingTotp(user, code, now);
            if (step.isEmpty()) {
                throw new ApiProblemException(TwoFactorProblem.INVALID_TOTP_CODE);
            }
            user.enableTotp(step.getAsLong(), now);
            List<String> codes = issueBackupCodes(user, now);
            notify(user, TwoFactorNoticeEvent.Kind.ENABLED, now, client, codes.size());
            sessions.revokeOthers(user.getId(), caller.sessionId(), SessionRevocationReason.SECURITY_CHANGE);
            return codes;
        });
    }

    /** Needs the password and a current TOTP or unused backup code. Refused for an ADMIN. */
    public void disable(SessionPrincipal caller, String password, String code, ClientAddress client) {
        consumeAttempt(caller);
        requirePassword(caller, password);
        transactions.executeWithoutResult(status -> {
            User user = lockedActiveUser(caller);
            if (!user.isTotpEnabled()) {
                throw new ApiProblemException(TwoFactorProblem.NOT_ENABLED);
            }
            if (user.getRole() == Role.ADMIN) {
                throw new ApiProblemException(TwoFactorProblem.REQUIRED_FOR_ADMIN);
            }
            Instant now = clock.instant();
            requireCode(user, code, now);
            user.disableTotp(now);
            backupCodes.deleteAllOf(user.getId());
            notify(user, TwoFactorNoticeEvent.Kind.DISABLED, now, client, 0);
            sessions.revokeOthers(user.getId(), caller.sessionId(), SessionRevocationReason.SECURITY_CHANGE);
        });
    }

    /** Replaces the whole backup code set; the old codes stop working in the same transaction. */
    public List<String> regenerateBackupCodes(SessionPrincipal caller, String password, String code,
                                              ClientAddress client) {
        consumeAttempt(caller);
        requirePassword(caller, password);
        return transactions.execute(status -> {
            User user = lockedActiveUser(caller);
            if (!user.isTotpEnabled()) {
                throw new ApiProblemException(TwoFactorProblem.NOT_ENABLED);
            }
            Instant now = clock.instant();
            requireCode(user, code, now);
            backupCodes.deleteAllOf(user.getId());
            List<String> codes = issueBackupCodes(user, now);
            notify(user, TwoFactorNoticeEvent.Kind.BACKUP_CODES_REGENERATED, now, client, codes.size());
            return codes;
        });
    }

    private void consumeAttempt(SessionPrincipal caller) {
        rateLimiter.consume(ACCOUNT_BUCKET, caller.userId().toString(), limits.twoFactorPerAccount());
    }

    /** Outside any transaction: Argon2id is slow, and must not hold a connection while it runs. */
    private void requirePassword(SessionPrincipal caller, String password) {
        User user = users.findById(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiProblemException(TwoFactorProblem.REAUTHENTICATION_FAILED);
        }
    }

    /** A wrong code rolls the transaction back, which is right: nothing was spent, and nothing else changed. */
    private void requireCode(User user, String code, Instant now) {
        if (verifier.verify(user, code, now).isEmpty()) {
            throw new ApiProblemException(TwoFactorProblem.REAUTHENTICATION_FAILED);
        }
    }

    private List<String> issueBackupCodes(User user, Instant now) {
        List<String> codes = BackupCodes.generate();
        for (String code : codes) {
            String normalised = BackupCodes.normalise(code).orElseThrow();
            backupCodes.save(TotpBackupCode.issue(user.getId(), backupCodeHasher.hash(user.getId(), normalised), now));
        }
        return codes;
    }

    private void notify(User user, TwoFactorNoticeEvent.Kind kind, Instant now, ClientAddress client,
                        int backupCodesLeft) {
        events.publishEvent(new TwoFactorNoticeEvent(user.getEmail(), user.getFullName(), kind, now,
                client.hostAddress(), backupCodesLeft));
    }

    private User activeUser(SessionPrincipal caller) {
        return users.findById(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
    }

    private User lockedActiveUser(SessionPrincipal caller) {
        return users.findByIdForUpdate(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
    }

    /**
     * The Key Uri Format authenticator apps read from a QR code. Label and
     * issuer are percent-encoded (spaces as %20, not +, which some apps show
     * literally); every TOTP parameter is spelled out even where it is the
     * default.
     */
    private String otpauthUri(String accountName, String secret) {
        String issuer = encode(properties.issuer());
        return "otpauth://totp/" + issuer + ":" + encode(accountName)
                + "?secret=" + secret
                + "&issuer=" + issuer
                + "&algorithm=" + Totp.ALGORITHM
                + "&digits=" + Totp.DIGITS
                + "&period=" + Totp.PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * @param setupPending a secret has been set up and is waiting for its first code
     */
    public record TwoFactorStatus(boolean enabled, boolean setupPending, int backupCodesRemaining) {
    }

    /** Shown once: the secret is never returned again after this response. */
    public record TwoFactorSetup(String secret, String otpauthUri, String issuer, String accountName) {

        @Override
        public String toString() {
            return "TwoFactorSetup[issuer=" + issuer + "]";
        }
    }
}
