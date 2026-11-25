package com.tripmate.compliance.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param requestLimit data requests and export downloads, per account
 * @param exportTtl    how long a finished export can be downloaded before it is cleared
 * @param jobInterval  how often waiting requests are processed
 * @param maxAttempts  a request that fails this many times is marked FAILED
 */
@ConfigurationProperties("tripmate.compliance")
public record ComplianceProperties(RateLimit requestLimit, Duration exportTtl, Duration jobInterval, int maxAttempts) {

    public ComplianceProperties {
        String prefix = "tripmate.compliance.";
        Objects.requireNonNull(requestLimit, prefix + "request-limit is required");
        Objects.requireNonNull(exportTtl, prefix + "export-ttl is required");
        Objects.requireNonNull(jobInterval, prefix + "job-interval is required");
        if (!exportTtl.isPositive() || maxAttempts < 1) {
            throw new IllegalArgumentException(prefix + "export-ttl or max-attempts out of range");
        }
    }
}