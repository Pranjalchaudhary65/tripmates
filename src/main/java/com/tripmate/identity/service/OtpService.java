package com.tripmate.identity.service;

import com.tripmate.identity.entity.OtpChannel;
import com.tripmate.identity.entity.OtpCode;
import com.tripmate.identity.entity.OtpPurpose;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.mail.VerificationCodeIssuedEvent;
import com.tripmate.identity.repository.OtpCodeRepository;
import com.tripmate.identity.repository.UserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, re-issues and redeems email verification codes.
 *
 * <p>The ID of a code row doubles as the verification ID handed to the
 * client that caused it to be issued. Redeeming needs that ID and the code
 * from the inbox, so verifying proves two things at once: control of the
 * inbox, and being the client that set the account's current password. That
 * second part blocks pre-registration hijacking - someone registering a
 * victim's address with their own password and waiting for the victim to
 * type in the code they receive.
 *
 * <p>Every method that changes state locks the user row first and the code
 * row second, in that order, so concurrent register / resend / verify calls
 * for one account queue up instead of deadlocking.
 */
@Service
@EnableConfigurationProperties(VerificationProperties.class)
public class OtpService {

    private static final Duration DAILY_WINDOW = Duration.ofDays(1);

    private final OtpCodeRepository codes;

    private final UserRepository users;

    private final OtpCodeHasher hasher;

    private final VerificationProperties properties;

    private final ApplicationEventPublisher events;

    private final Clock clock;

    public OtpService(OtpCodeRepository codes, UserRepository users, OtpCodeHasher hasher,
                      VerificationProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.codes = codes;
        this.users = users;
        this.hasher = hasher;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /** Whether one more code can be sent to this account today. Caller must hold the user row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean canIssueEmailVerification(UUID userId) {
        Instant since = clock.instant().minus(DAILY_WINDOW);
        return codes.countIssuedSince(userId, OtpChannel.EMAIL, OtpPurpose.VERIFY, since) < properties.maxCodesPerDay();
    }

    /**
     * Supersedes the account's current code, stores the hash of a new one and
     * queues the email. The caller must hold the user row lock and have
     * checked {@link #canIssueEmailVerification}.
     *
     * @return the new verification ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID issueEmailVerification(User user) {
        Instant now = clock.instant();
        latestEmailVerification(user.getId()).ifPresent(previous -> previous.supersede(now));

        String code = hasher.generateCode();
        OtpCode otp = codes.save(OtpCode.issue(user.getId(), OtpChannel.EMAIL, OtpPurpose.VERIFY,
                hasher.hash(user.getId(), OtpPurpose.VERIFY, code),
                properties.codeTtl(), properties.maxAttempts(), now));

        events.publishEvent(new VerificationCodeIssuedEvent(
                user.getEmail(), user.getFullName(), code, properties.codeTtl()));
        return otp.getId();
    }

    /**
     * Sends a fresh code to the account behind {@code previousId}, but only
     * if {@code previousId} is that account's newest code - a holder of an
     * older ID belongs to an earlier registration whose password has since
     * been replaced. Within the cooldown, or past the daily cap, the current
     * ID is returned and nothing is sent.
     *
     * @return the ID to verify with; a random decoy when {@code previousId}
     *         is unknown, superseded or the account is already verified, so
     *         the answer never reveals which
     */
    @Transactional
    public UUID resendEmailVerification(UUID previousId) {
        Optional<User> pendingUser = lockOwner(previousId).filter(User::isPendingEmailVerification);
        if (pendingUser.isEmpty()) {
            return UUID.randomUUID();
        }
        User user = pendingUser.get();

        OtpCode latest = latestEmailVerification(user.getId()).orElse(null);
        if (latest == null || !latest.getId().equals(previousId)) {
            return UUID.randomUUID();
        }
        Instant now = clock.instant();
        boolean coolingDown = latest.getCreatedAt().plus(properties.resendCooldown()).isAfter(now);
        if (coolingDown || !canIssueEmailVerification(user.getId())) {
            return latest.getId();
        }
        return issueEmailVerification(user);
    }

    /**
     * Redeems a code. Every failure - unknown ID, wrong code, expired, used,
     * superseded, out of attempts - returns false, and the caller gives one
     * answer for all of them.
     *
     * <p>The attempt counter is written in the same transaction as the
     * comparison, and that transaction commits whether or not the code was
     * right: this method reports failure by return value, not by throwing,
     * precisely so a wrong guess is not rolled back along with the error.
     */
    @Transactional
    public boolean verifyEmail(UUID verificationId, String code) {
        Optional<User> user = lockOwner(verificationId);
        if (user.isEmpty()) {
            return false;
        }
        OtpCode otp = codes.findByIdForUpdate(verificationId).orElseThrow();

        Instant now = clock.instant();
        if (!otp.isRedeemable(now)) {
            return false;
        }
        otp.recordAttempt();
        if (!hasher.matches(otp.getUserId(), OtpPurpose.VERIFY, code, otp.getCodeHash())) {
            return false;
        }
        if (!user.get().isPendingEmailVerification()) {
            return false;
        }
        otp.consume(now);
        user.get().markEmailVerified(now);
        return true;
    }

    /** Locks the user an email verification code belongs to, before any code row is loaded. */
    private Optional<User> lockOwner(UUID verificationId) {
        return codes.findOwnerId(verificationId, OtpChannel.EMAIL, OtpPurpose.VERIFY)
                .flatMap(users::findByIdForUpdate);
    }

    private Optional<OtpCode> latestEmailVerification(UUID userId) {
        return codes.findFirstByUserIdAndChannelAndPurposeOrderByCreatedAtDesc(
                userId, OtpChannel.EMAIL, OtpPurpose.VERIFY);
    }
}
