package com.tripmate.identity.service;

import com.tripmate.identity.captcha.CaptchaVerifier;
import com.tripmate.identity.dto.LoginRequest;
import com.tripmate.identity.entity.LoginAttempt;
import com.tripmate.identity.entity.LoginFailureReason;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.mail.NewDeviceLoginEvent;
import com.tripmate.identity.repository.LoginHistoryRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Checks an email and password without revealing which part was wrong, or
 * whether the email has an account at all.
 *
 * <p>Every attempt that gets past the rate limits does the same work, in the
 * same order, whatever the outcome: one account lookup, one charge against
 * the lockout counter, and exactly one Argon2id verification - against a
 * dummy hash when the email has no account, and still against the real hash
 * when the account is locked. Then the outcome is decided. An unknown email,
 * a wrong password, an unverified email, a disabled account and a locked
 * account all end in the same 401 with the same body; only login_history
 * records which it was.
 *
 * <p>Counters are keyed on the typed email, not the account, so an email
 * with no account behaves exactly like one with an account: it is
 * rate-limited, asked for a CAPTCHA and "locked" on the same schedule.
 *
 * <p>A browser that has already signed in to the account successfully - it
 * presents a device cookie whose hash login_history has a success for - is
 * counted separately from everyone else. Someone guessing a victim's password
 * from elsewhere can lock the account for unknown browsers, but the victim's
 * own browser keeps its own counter and can still sign in.
 */
@Service
@EnableConfigurationProperties({LoginProperties.class, AuthRateLimitProperties.class})
public class LoginService {

    static final String ACCOUNT_BUCKET = "login-account";

    /** Never a real user ID, so the known-device query runs (and finds nothing) for an unknown email too. */
    private static final UUID NO_USER = new UUID(0, 0);

    private final UserRepository users;

    private final LoginHistoryRepository history;

    private final LoginLockout lockout;

    private final RateLimiter rateLimiter;

    private final CaptchaVerifier captcha;

    private final PasswordEncoder passwordEncoder;

    private final LoginProperties properties;

    private final AuthRateLimitProperties limits;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final Clock clock;

    /**
     * Same encoder, so the same Argon2id parameters and the same cost as a
     * real hash. Of a random password nobody knows, so it matches nothing.
     */
    private final String dummyHash;

    public LoginService(UserRepository users, LoginHistoryRepository history, LoginLockout lockout,
                        RateLimiter rateLimiter, CaptchaVerifier captcha, PasswordEncoder passwordEncoder,
                        LoginProperties properties, AuthRateLimitProperties limits,
                        ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.history = history;
        this.lockout = lockout;
        this.rateLimiter = rateLimiter;
        this.captcha = captcha;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.limits = limits;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(DeviceIds.generate());
    }

    /**
     * Not transactional: Argon2id takes tens of milliseconds and must not hold
     * a database connection. Only the success bookkeeping runs in a
     * transaction.
     *
     * @throws ApiProblemException {@link LoginProblem#INVALID_CREDENTIALS} for
     *         every kind of refusal, or {@link LoginProblem#CAPTCHA_REQUIRED}
     * @throws com.tripmate.platform.error.RetryLaterException over the
     *         per-account limit (429), or when Redis is unreachable (503)
     */
    public LoginResult login(LoginRequest request, LoginClient client) {
        String email = request.email().trim();
        User user = users.findLiveByEmail(email).orElse(null);

        String presentedDeviceHash = client.deviceId() == null ? null : DeviceIds.hash(client.deviceId());
        boolean knownDevice = presentedDeviceHash != null && history.existsByUserIdAndDeviceIdHashAndSuccessTrue(
                user == null ? NO_USER : user.getId(), presentedDeviceHash);
        String counterKey = counterKey(email, knownDevice ? presentedDeviceHash : null);

        rateLimiter.consume(ACCOUNT_BUCKET, counterKey, limits.loginPerAccount());

        Attempt attempt = new Attempt(user, email, client, presentedDeviceHash);
        if (lockout.failures(counterKey) >= properties.captchaAfterFailures()
                && !captcha.verify(request.captchaToken(), client.address())) {
            attempt.refuse(LoginFailureReason.CAPTCHA_FAILED);
            throw new ApiProblemException(LoginProblem.CAPTCHA_REQUIRED);
        }

        LoginLockout.Attempt charged = lockout.charge(counterKey, clock.instant());
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                user == null ? dummyHash : user.getPasswordHash());

        LoginFailureReason failure = failureReason(charged, user, passwordMatches);
        if (failure != null) {
            attempt.refuse(failure);
            throw new ApiProblemException(LoginProblem.INVALID_CREDENTIALS);
        }
        lockout.reset(counterKey);
        return succeed(user, email, client);
    }

    private static LoginFailureReason failureReason(LoginLockout.Attempt charged, User user, boolean passwordMatches) {
        if (!charged.isAllowed()) {
            return LoginFailureReason.LOCKED;
        }
        if (user == null) {
            return LoginFailureReason.UNKNOWN_EMAIL;
        }
        if (!passwordMatches) {
            return LoginFailureReason.BAD_PASSWORD;
        }
        if (!user.isActive()) {
            return LoginFailureReason.ACCOUNT_DISABLED;
        }
        if (!user.isEmailVerified()) {
            return LoginFailureReason.EMAIL_NOT_VERIFIED;
        }
        return null;
    }

    /**
     * Keeps the browser's existing device ID even when it is new to this
     * account (another account signed in on the same browser before): the ID
     * names the browser, not the person.
     *
     * <p>The user row is locked so two concurrent first sign-ins from the
     * same new browser record one "new device" between them, not two.
     */
    private LoginResult succeed(User user, String email, LoginClient client) {
        String deviceId = client.deviceId() != null ? client.deviceId() : DeviceIds.generate();
        String deviceHash = DeviceIds.hash(deviceId);
        Instant now = clock.instant();

        transactions.executeWithoutResult(status -> {
            users.findByIdForUpdate(user.getId());
            boolean seenBefore = history.existsByUserIdAndDeviceIdHashAndSuccessTrue(user.getId(), deviceHash);
            boolean firstSignIn = !history.existsByUserIdAndSuccessTrue(user.getId());
            history.save(LoginAttempt.succeeded(user.getId(), email, client.address().address(),
                    client.userAgent(), deviceHash, now));
            // The very first sign-in is from a new device by definition; alerting
            // on it would only teach people to ignore the alert.
            if (!seenBefore && !firstSignIn) {
                events.publishEvent(new NewDeviceLoginEvent(user.getEmail(), user.getFullName(), now,
                        client.address().hostAddress()));
            }
        });
        return new LoginResult(user.getId(), user.getIdVerificationTier(), deviceId);
    }

    /**
     * SHA-256 of the lower-cased email rather than the email itself: Redis
     * keys show up in monitoring and dumps, and need not carry addresses.
     */
    static String counterKey(String email, String knownDeviceHash) {
        String emailKey = DeviceIds.sha256Hex(email.toLowerCase(Locale.ROOT));
        return knownDeviceHash == null ? emailKey : emailKey + ":device:" + knownDeviceHash;
    }

    /** The request facts every refusal is recorded with. */
    private final class Attempt {

        private final User user;

        private final String email;

        private final LoginClient client;

        private final String deviceHash;

        private Attempt(User user, String email, LoginClient client, String deviceHash) {
            this.user = user;
            this.email = email;
            this.client = client;
            this.deviceHash = deviceHash;
        }

        void refuse(LoginFailureReason reason) {
            history.save(LoginAttempt.failed(user == null ? null : user.getId(), email,
                    client.address().address(), client.userAgent(), deviceHash, reason, clock.instant()));
        }
    }
}
