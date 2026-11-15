package com.tripmate.platform.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * A token bucket: up to {@code capacity} requests at once, refilled
 * continuously at {@code capacity} per {@code period}. Continuous ("greedy")
 * refill rather than all at once at the end of each period, so there is no
 * window boundary where a client gets two full buckets back to back.
 */
public record RateLimit(long capacity, Duration period) {

    public RateLimit {
        if (capacity < 1) {
            throw new IllegalArgumentException("Rate limit capacity must be positive");
        }
        Objects.requireNonNull(period, "Rate limit period is required");
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("Rate limit period must be positive");
        }
    }
}
