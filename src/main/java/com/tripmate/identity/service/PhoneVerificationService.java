package com.tripmate.identity.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.IdentityVerification;
import com.tripmate.identity.entity.OtpChannel;
import com.tripmate.identity.entity.OtpCode;
import com.tripmate.identity.entity.OtpPurpose;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.phone.PhoneBlindIndex;
import com.tripmate.identity.phone.PhoneCodeIssuedEvent;
import com.tripmate.identity.phone.PhoneNumbers;
import com.tripmate.identity.repository.IdentityVerificationRepository;
import com.tripmate.identity.repository.OtpCodeRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.Constraints;
import com.tripmate.platform.error.RetryLaterException;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The PHONE tier: send a code to a number, confirm it. The number is stored
 * encrypted under the user's data key, with a keyed blind index beside it so
 * "already verified on another account" never needs a decryption.
 *
 * <p>A code is sent to any well-formed number, whoever else uses it: only
 * once the right code comes back, proving the caller holds that phone, is
 * the caller told the number is taken. Telling anyone earlier would turn this
 * route into a lookup of which numbers have accounts.
 *
 * <p>Codes reuse otp_codes and the email code rules (lifetime, 5 attempts
 * counted under a row lock, cooldown, daily cap), with the PHONE channel, so
 * an email code can never be redeemed here or the other way round.
 */
@Service
@EnableConfigurationProperties({VerificationProperties.class, VerificationLimitProperties.class})
public class PhoneVerificationService {

    static final String CODE_BUCKET = "phone-code-account";

    static final String CONFIRM_BUCKET = "phone-confirm-account";

    private static final String VERIFIED_PHONE_INDEX = "idx_users_phone_verified_unique";

    private static final Duration DAILY_WINDOW = Duration.ofDays(1);

    private final UserRepository users;

    private final OtpCodeRepository codes;

    private final IdentityVerificationRepository verifications;

    private final OtpCodeHasher hasher;

    private final FieldEncryption encryption;

    private final PhoneBlindIndex blindIndex;

    private final TrustInputEvents trustInputs;

    private final RateLimiter rateLimiter;

    private final VerificationLimitProperties limits;

    private final VerificationProperties codeRules;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public PhoneVerificationService(UserRepository users, OtpCodeRepository codes,
                                    IdentityVerificationRepository verifications, OtpCodeHasher hasher,
                                    FieldEncryption encryption, PhoneBlindIndex blindIndex,
                                    TrustInputEvents trustInputs, RateLimiter rateLimiter,
                                    VerificationLimitProperties limits, VerificationProperties codeRules,
                                    ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.users = users;
        this.codes = codes;
        this.verifications = verifications;
        this.hasher = hasher;
        this.encryption = encryption;
        this.blindIndex = blindIndex;
        this.trustInputs = trustInputs;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.codeRules = codeRules;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Stores the number as pending and sends it a code, superseding any
     * earlier code.
     */
    public PhoneCodeSent sendCode(SessionPrincipal caller, String phoneNumber) {
        rateLimiter.consume(CODE_BUCKET, caller.userId().toString(), limits.phoneCode());
        String e164 = PhoneNumbers.normalise(phoneNumber)
                .orElseThrow(() -> new ApiProblemException(VerificationProblem.INVALID_PHONE_NUMBER));
        return transactions.execute(status -> {
            User user = lockedActiveUser(caller);
            if (user.isPhoneVerified()) {
                throw new ApiProblemException(VerificationProblem.PHONE_ALREADY_VERIFIED);
            }
            Instant now = clock.instant();
            Optional<OtpCode> latest = codes.findFirstByUserIdAndChannelAndPurposeOrderByCreatedAtDesc(
                    user.getId(), OtpChannel.PHONE, OtpPurpose.VERIFY);
            refuseWithinCooldown(latest, now);
            refuseOverDailyCap(user.getId(), now);
            latest.ifPresent(previous -> previous.supersede(now));

            user.startPhoneVerification(
                    encryption.encrypt(user, EncryptedField.PHONE, e164.getBytes(StandardCharsets.UTF_8)),
                    blindIndex.of(e164), now);
            String code = hasher.generateCode();
            OtpCode otp = codes.save(OtpCode.issue(user.getId(), OtpChannel.PHONE, OtpPurpose.VERIFY,
                    hasher.hash(user.getId(), OtpPurpose.VERIFY, code), codeRules.codeTtl(),
                    codeRules.maxAttempts(), now));
            events.publishEvent(new PhoneCodeIssuedEvent(user.getEmail(), user.getFullName(), e164, code,
                    codeRules.codeTtl()));
            return new PhoneCodeSent(otp.getId(), PhoneNumbers.mask(e164), codeRules.codeTtl(),
                    codeRules.resendCooldown());
        });
    }

    /**
     * Confirms the pending number with its code and raises the account to
     * PHONE. A wrong code is counted against the code even though the call
     * fails, which is why the failure is decided inside the transaction but
     * thrown only after it commits.
     */
    public void confirm(SessionPrincipal caller, UUID verificationId, String code) {
        rateLimiter.consume(CONFIRM_BUCKET, caller.userId().toString(), limits.phoneConfirm());
        Outcome outcome;
        try {
            outcome = transactions.execute(status -> confirmLocked(caller, verificationId, code));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, VERIFIED_PHONE_INDEX)) {
                throw new ApiProblemException(VerificationProblem.PHONE_IN_USE);
            }
            throw e;
        }
        switch (outcome) {
            case INVALID_CODE -> throw new ApiProblemException(VerificationProblem.INVALID_PHONE_CODE);
            case IN_USE -> throw new ApiProblemException(VerificationProblem.PHONE_IN_USE);
            case VERIFIED -> {
            }
        }
    }

    private Outcome confirmLocked(SessionPrincipal caller, UUID verificationId, String code) {
        User user = lockedActiveUser(caller);
        if (user.isPhoneVerified()) {
            throw new ApiProblemException(VerificationProblem.PHONE_ALREADY_VERIFIED);
        }
        Optional<OtpCode> found = codes.findByIdForUpdate(verificationId)
                .filter(otp -> otp.getUserId().equals(user.getId()))
                .filter(otp -> otp.getChannel() == OtpChannel.PHONE && otp.getPurpose() == OtpPurpose.VERIFY);
        Instant now = clock.instant();
        if (found.isEmpty() || !found.get().isRedeemable(now) || user.getPhoneBlindIndex() == null) {
            return Outcome.INVALID_CODE;
        }
        OtpCode otp = found.get();
        otp.recordAttempt();
        if (!hasher.matches(user.getId(), OtpPurpose.VERIFY, code, otp.getCodeHash())) {
            return Outcome.INVALID_CODE;
        }
        otp.consume(now);
        if (users.isPhoneVerifiedByAnother(user.getPhoneBlindIndex(), user.getId())) {
            return Outcome.IN_USE;
        }
        VerificationTier before = user.markPhoneVerified(now);
        verifications.save(IdentityVerification.phoneConfirmed(user.getId(), now));
        trustInputs.tierChanged(user.getId(), before, user.getIdVerificationTier());
        return Outcome.VERIFIED;
    }

    private void refuseWithinCooldown(Optional<OtpCode> latest, Instant now) {
        if (latest.isEmpty()) {
            return;
        }
        Instant nextAllowed = latest.get().getCreatedAt().plus(codeRules.resendCooldown());
        if (nextAllowed.isAfter(now)) {
            throw new RetryLaterException(VerificationProblem.PHONE_CODE_COOLDOWN, Duration.between(now, nextAllowed));
        }
    }

    private void refuseOverDailyCap(UUID userId, Instant now) {
        Instant since = now.minus(DAILY_WINDOW);
        if (codes.countIssuedSince(userId, OtpChannel.PHONE, OtpPurpose.VERIFY, since) < codeRules.maxCodesPerDay()) {
            return;
        }
        Duration wait = codes.findFirstByUserIdAndChannelAndPurposeAndCreatedAtAfterOrderByCreatedAtAsc(
                        userId, OtpChannel.PHONE, OtpPurpose.VERIFY, since)
                .map(oldest -> Duration.between(now, oldest.getCreatedAt().plus(DAILY_WINDOW)))
                .orElse(DAILY_WINDOW);
        throw new RetryLaterException(VerificationProblem.PHONE_CODE_DAILY_LIMIT, wait);
    }

    private User lockedActiveUser(SessionPrincipal caller) {
        return users.findByIdForUpdate(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
    }

    private enum Outcome {
        VERIFIED, INVALID_CODE, IN_USE
    }

    /**
     * @param verificationId what the code is confirmed against
     * @param maskedNumber   the number as the client may show it
     */
    public record PhoneCodeSent(UUID verificationId, String maskedNumber, Duration expiresIn, Duration resendAfter) {
    }
}
