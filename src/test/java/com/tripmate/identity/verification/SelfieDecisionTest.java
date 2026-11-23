package com.tripmate.identity.verification;

import com.tripmate.identity.entity.VerificationRejection;
import com.tripmate.identity.entity.VerificationStatus;
import com.tripmate.identity.upload.ImageSanitizer;
import com.tripmate.support.TestImages;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The mock provider and the thresholds together, on images that went through the sanitiser as uploads do. */
class SelfieDecisionTest {

    private final ImageSanitizer sanitizer = new ImageSanitizer();

    private final LivenessVerifier verifier = new ImageHashLivenessVerifier();

    private final SelfieDecisionPolicy policy = new SelfieDecisionPolicy(
            new SelfieProperties(Duration.ofDays(7), Duration.ofMinutes(2), 0.85, 0.60, 100, "mock"));

    @Test
    void theSameFaceWithMovementIsApproved() {
        LivenessVerifier.Assessment assessment = assess(0, 1);

        assertThat(assessment.live()).isTrue();
        assertThat(assessment.similarity()).isEqualTo(1.0);
        assertThat(policy.decide(assessment)).isEqualTo(
                new SelfieDecisionPolicy.Decision(VerificationStatus.APPROVED, null));
    }

    @Test
    void anInBetweenMatchGoesToAReviewer() {
        LivenessVerifier.Assessment assessment = assess(2, 3);

        assertThat(assessment.similarity()).isEqualTo(0.75);
        assertThat(policy.decide(assessment).status()).isEqualTo(VerificationStatus.PENDING);
    }

    @Test
    void aClearMismatchIsRejected() {
        assertThat(policy.decide(assess(8, 7))).isEqualTo(
                new SelfieDecisionPolicy.Decision(VerificationStatus.REJECTED, VerificationRejection.LOW_SIMILARITY));
    }

    @Test
    void framesWithNoMovementFailLivenessEvenWhenTheFaceMatches() {
        LivenessVerifier.Assessment assessment = assess(0, 0);

        assertThat(assessment.live()).isFalse();
        assertThat(policy.decide(assessment)).isEqualTo(
                new SelfieDecisionPolicy.Decision(VerificationStatus.REJECTED, VerificationRejection.LIVENESS_FAILED));
    }

    @Test
    void theThresholdsAreInclusiveAtApproveAndExclusiveAtReject() {
        assertThat(policy.decide(new LivenessVerifier.Assessment(true, 0.85)).status())
                .isEqualTo(VerificationStatus.APPROVED);
        assertThat(policy.decide(new LivenessVerifier.Assessment(true, 0.60)).status())
                .isEqualTo(VerificationStatus.PENDING);
        assertThat(policy.decide(new LivenessVerifier.Assessment(true, 0.599)).status())
                .isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    void thresholdsOutOfOrderStopStartup() {
        assertThatThrownBy(() -> new SelfieProperties(Duration.ofDays(7), Duration.ofMinutes(2), 0.5, 0.6, 100,
                "mock")).hasMessageContaining("reject-below <= approve-at");
    }

    private LivenessVerifier.Assessment assess(int neutralRows, int promptRows) {
        BufferedImage reference = sanitizer.read(sanitizer.sanitize(TestImages.profilePhoto()).jpeg());
        BufferedImage neutral = sanitizer.sanitize(TestImages.jpeg(neutralRows)).pixels();
        BufferedImage prompt = sanitizer.sanitize(TestImages.jpeg(promptRows)).pixels();
        return verifier.assess(new LivenessVerifier.Evidence(LivenessPrompt.SMILE, neutral, prompt, reference));
    }
}
