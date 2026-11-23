package com.tripmate.identity.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Selfie check rules.
 *
 * @param retention         how long a selfie image is kept, whatever the outcome
 * @param challengeTtl      how long a started check waits for its frames
 * @param approveAt         similarity at or above which a live selfie is approved without a reviewer
 * @param rejectBelow       similarity below which it is rejected without a reviewer; in between goes to review
 * @param purgeBatchSize    rows the purge job handles per transaction
 * @param livenessProvider  which {@link LivenessVerifier} runs; an unknown value stops startup
 */
@ConfigurationProperties("tripmate.identity.selfie")
public record SelfieProperties(Duration retention, Duration challengeTtl, double approveAt, double rejectBelow,
                               int purgeBatchSize, String livenessProvider) {

    public SelfieProperties {
        String prefix = "tripmate.identity.selfie.";
        Objects.requireNonNull(retention, prefix + "retention is required");
        Objects.requireNonNull(challengeTtl, prefix + "challenge-ttl is required");
        if (retention.isNegative() || retention.isZero() || challengeTtl.isNegative() || challengeTtl.isZero()) {
            throw new IllegalArgumentException(prefix + "retention and challenge-ttl must be positive");
        }
        if (!(0 < rejectBelow && rejectBelow <= approveAt && approveAt <= 1)) {
            throw new IllegalArgumentException(prefix + "needs 0 < reject-below <= approve-at <= 1");
        }
        if (purgeBatchSize < 1) {
            throw new IllegalArgumentException(prefix + "purge-batch-size must be positive");
        }
        if (livenessProvider == null || livenessProvider.isBlank()) {
            throw new IllegalArgumentException(prefix + "liveness-provider is required");
        }
    }
}
