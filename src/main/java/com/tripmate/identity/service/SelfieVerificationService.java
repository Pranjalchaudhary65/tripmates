package com.tripmate.identity.service;

import com.tripmate.identity.entity.IdentityVerification;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.VerificationRejection;
import com.tripmate.identity.entity.VerificationStatus;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.repository.IdentityVerificationRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.identity.upload.ImageSanitizer;
import com.tripmate.identity.upload.MediaKind;
import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.identity.verification.LivenessPrompt;
import com.tripmate.identity.verification.LivenessVerifier;
import com.tripmate.identity.verification.SelfieChallenges;
import com.tripmate.identity.verification.SelfieDecisionPolicy;
import com.tripmate.identity.verification.SelfieProperties;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.Constraints;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

/**
 * The SELFIE_MATCH tier. A check starts with a server-chosen prompt; the
 * client then sends two frames captured in the app - one looking at the
 * camera, one following the prompt - within the check's time limit.
 *
 * <p>The liveness provider runs outside any transaction (a real one is a
 * network call), so everything it was judged against is checked again under
 * the user row lock before the outcome is recorded: still eligible, and
 * still the same profile photo.
 *
 * <p>Only the neutral frame is stored, under a random key, with a purge
 * deadline from the moment it is written. The prompted frame is used for
 * the liveness decision and dropped. The similarity score is never returned
 * to the user: an exact number would let someone tune a borrowed photo until
 * it passes.
 */
@Service
@EnableConfigurationProperties({SelfieProperties.class, VerificationLimitProperties.class})
public class SelfieVerificationService {

    static final String CHALLENGE_BUCKET = "selfie-challenge-account";

    static final String SUBMISSION_BUCKET = "selfie-submission-account";

    private static final String ONE_PENDING_INDEX = "idx_idverif_one_pending_selfie";

    private static final LivenessPrompt[] PROMPTS = LivenessPrompt.values();

    private final SecureRandom random = new SecureRandom();

    private final UserRepository users;

    private final IdentityVerificationRepository verifications;

    private final SelfieChallenges challenges;

    private final LivenessVerifier liveness;

    private final SelfieDecisionPolicy policy;

    private final ImageSanitizer sanitizer;

    private final ObjectStorage storage;

    private final TrustInputEvents trustInputs;

    private final RateLimiter rateLimiter;

    private final VerificationLimitProperties limits;

    private final SelfieProperties properties;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Clock clock;

    public SelfieVerificationService(UserRepository users, IdentityVerificationRepository verifications,
                                     SelfieChallenges challenges, LivenessVerifier liveness,
                                     SelfieDecisionPolicy policy, ImageSanitizer sanitizer, ObjectStorage storage,
                                     TrustInputEvents trustInputs, RateLimiter rateLimiter,
                                     VerificationLimitProperties limits, SelfieProperties properties,
                                     PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.verifications = verifications;
        this.challenges = challenges;
        this.liveness = liveness;
        this.policy = policy;
        this.sanitizer = sanitizer;
        this.storage = storage;
        this.trustInputs = trustInputs;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    public SelfieChallenges.Challenge start(SessionPrincipal caller) {
        rateLimiter.consume(CHALLENGE_BUCKET, caller.userId().toString(), limits.selfieChallenge());
        readOnly.executeWithoutResult(status -> requireEligible(activeUser(caller)));
        return challenges.open(caller.userId(), PROMPTS[random.nextInt(PROMPTS.length)], clock.instant());
    }

    /**
     * Runs the check and records its outcome. Rejections are outcomes, not
     * errors: the call succeeds and says REJECTED, with a reason from a fixed
     * list.
     */
    public Outcome submit(SessionPrincipal caller, String challengeId, byte[] neutralUpload, byte[] promptUpload) {
        rateLimiter.consume(SUBMISSION_BUCKET, caller.userId().toString(), limits.selfieSubmission());
        LivenessPrompt prompt = challenges.take(challengeId, caller.userId(), clock.instant())
                .orElseThrow(() -> new ApiProblemException(VerificationProblem.LIVENESS_CHALLENGE_INVALID));
        ImageSanitizer.SanitizedImage neutral = sanitizer.sanitize(neutralUpload);
        ImageSanitizer.SanitizedImage prompted = sanitizer.sanitize(promptUpload);

        String referenceKey = readOnly.execute(status -> {
            User user = activeUser(caller);
            requireEligible(user);
            return user.getProfilePhotoKey();
        });
        BufferedImage reference = sanitizer.read(storage.get(referenceKey)
                .orElseThrow(() -> new IllegalStateException("The profile photo is missing from storage")));

        LivenessVerifier.Assessment assessment = liveness.assess(
                new LivenessVerifier.Evidence(prompt, neutral.pixels(), prompted.pixels(), reference));
        SelfieDecisionPolicy.Decision decision = policy.decide(assessment);
        BigDecimal similarity = BigDecimal.valueOf(assessment.similarity()).setScale(3, RoundingMode.HALF_EVEN);

        String imageKey = MediaKind.SELFIE.newKey();
        storage.put(imageKey, neutral.jpeg());
        try {
            return transactions.execute(status -> record(caller, referenceKey, imageKey, similarity, decision));
        } catch (DataIntegrityViolationException e) {
            storage.delete(imageKey);
            if (Constraints.violated(e, ONE_PENDING_INDEX)) {
                throw new ApiProblemException(VerificationProblem.SELFIE_REVIEW_PENDING);
            }
            throw e;
        } catch (RuntimeException e) {
            storage.delete(imageKey);
            throw e;
        }
    }

    private Outcome record(SessionPrincipal caller, String referenceKey, String imageKey, BigDecimal similarity,
                           SelfieDecisionPolicy.Decision decision) {
        User user = users.findByIdForUpdate(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        requireEligible(user);
        if (!referenceKey.equals(user.getProfilePhotoKey())) {
            throw new ApiProblemException(VerificationProblem.LIVENESS_CHALLENGE_INVALID);
        }
        Instant now = clock.instant();
        IdentityVerification verification = verifications.save(IdentityVerification.selfie(user.getId(), imageKey,
                similarity, decision.status(), decision.rejection(), properties.retention(), now));
        if (decision.status() == VerificationStatus.APPROVED) {
            VerificationTier before = user.markSelfieMatched(now);
            trustInputs.tierChanged(user.getId(), before, user.getIdVerificationTier());
        }
        return new Outcome(verification.getStatus(), verification.getRejectionReason());
    }

    /** The checks a selfie must pass before it is worth a liveness call. */
    private void requireEligible(User user) {
        if (user.getIdVerificationTier() == VerificationTier.SELFIE_MATCH) {
            throw new ApiProblemException(VerificationProblem.SELFIE_ALREADY_VERIFIED);
        }
        if (!user.isPhoneVerified()) {
            throw new ApiProblemException(VerificationProblem.PHONE_REQUIRED);
        }
        if (user.getProfilePhotoKey() == null) {
            throw new ApiProblemException(VerificationProblem.PROFILE_PHOTO_REQUIRED);
        }
        boolean pending = verifications.findFirstByUserIdAndTierOrderByCreatedAtDesc(user.getId(),
                VerificationTier.SELFIE_MATCH).filter(IdentityVerification::isPending).isPresent();
        if (pending) {
            throw new ApiProblemException(VerificationProblem.SELFIE_REVIEW_PENDING);
        }
    }

    private User activeUser(SessionPrincipal caller) {
        return users.findById(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
    }

    /** @param rejection set only for REJECTED; PENDING means a reviewer will decide */
    public record Outcome(VerificationStatus status, VerificationRejection rejection) {
    }
}
