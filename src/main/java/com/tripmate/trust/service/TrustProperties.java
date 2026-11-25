package com.tripmate.trust.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param readLimit     the trust score and review screens, per account
 * @param writeLimit    review submissions, per account
 * @param jobInterval   how often queued scores are recomputed
 * @param jobBatch      scores one run recomputes at most
 * @param refreshAfter  a score older than this is recomputed even with no new input, since inputs fade
 * @param reviewWindow  how long after a trip ends its travellers can review each other; hidden reviews are
 *                      revealed when it closes
 */
@ConfigurationProperties("tripmate.trust")
public record TrustProperties(RateLimit readLimit, RateLimit writeLimit, Duration jobInterval, int jobBatch,
                              Duration refreshAfter, Duration reviewWindow) {

    public TrustProperties {
        String prefix = "tripmate.trust.";
        Objects.requireNonNull(readLimit, prefix + "read-limit is required");
        Objects.requireNonNull(writeLimit, prefix + "write-limit is required");
        Objects.requireNonNull(jobInterval, prefix + "job-interval is required");
        Objects.requireNonNull(refreshAfter, prefix + "refresh-after is required");
        Objects.requireNonNull(reviewWindow, prefix + "review-window is required");
        if (jobBatch < 1 || !refreshAfter.isPositive() || reviewWindow.toDays() < 1) {
            throw new IllegalArgumentException(prefix + "job-batch, refresh-after or review-window out of range");
        }
    }
}
