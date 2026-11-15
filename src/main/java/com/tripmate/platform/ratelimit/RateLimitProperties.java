package com.tripmate.platform.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param storeTimeout          longest one Redis round trip for a bucket may take
 * @param unavailableRetryAfter Retry-After sent when Redis cannot be reached and the request is refused
 */
@ConfigurationProperties("tripmate.rate-limit")
public record RateLimitProperties(Duration storeTimeout, Duration unavailableRetryAfter) {

    public RateLimitProperties {
        Objects.requireNonNull(storeTimeout, "tripmate.rate-limit.store-timeout is required");
        Objects.requireNonNull(unavailableRetryAfter, "tripmate.rate-limit.unavailable-retry-after is required");
    }
}
