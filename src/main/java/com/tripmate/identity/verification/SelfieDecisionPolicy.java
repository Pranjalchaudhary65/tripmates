package com.tripmate.identity.verification;

import com.tripmate.identity.entity.VerificationRejection;
import com.tripmate.identity.entity.VerificationStatus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Turns an assessment into an outcome. Clear cases are decided at once;
 * only a live selfie whose similarity falls between the two thresholds goes
 * to a person. A failed liveness check is never sent for review: a reviewer
 * looking at one still image cannot tell a live face from a photo of one
 * either.
 */
@Component
@EnableConfigurationProperties(SelfieProperties.class)
public class SelfieDecisionPolicy {

    private final SelfieProperties properties;

    public SelfieDecisionPolicy(SelfieProperties properties) {
        this.properties = properties;
    }

    public Decision decide(LivenessVerifier.Assessment assessment) {
        if (!assessment.live()) {
            return new Decision(VerificationStatus.REJECTED, VerificationRejection.LIVENESS_FAILED);
        }
        if (assessment.similarity() >= properties.approveAt()) {
            return new Decision(VerificationStatus.APPROVED, null);
        }
        if (assessment.similarity() < properties.rejectBelow()) {
            return new Decision(VerificationStatus.REJECTED, VerificationRejection.LOW_SIMILARITY);
        }
        return new Decision(VerificationStatus.PENDING, null);
    }

    /** @param rejection set only when {@code status} is REJECTED */
    public record Decision(VerificationStatus status, VerificationRejection rejection) {
    }
}
