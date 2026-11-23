package com.tripmate.vibe.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * @param writeLimit every change to the caller's quiz draft, profile or
 *                   deal-breakers, per account. Drafts are saved after each
 *                   quiz step, so it is generous; it exists so one account
 *                   cannot turn the outbox into a firehose for the matcher.
 */
@ConfigurationProperties("tripmate.vibe")
public record VibeProperties(RateLimit writeLimit) {

    public VibeProperties {
        Objects.requireNonNull(writeLimit, "tripmate.vibe.write-limit is required");
    }
}
