package com.tripmate.identity.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param issuer               the name authenticator apps show next to the code
 * @param challengeTtl         how long a password-verified login waits for its second factor
 * @param challengeMaxAttempts codes one login challenge accepts before it is discarded
 */
@ConfigurationProperties("tripmate.identity.two-factor")
public record TwoFactorProperties(String issuer, Duration challengeTtl, int challengeMaxAttempts) {

    private static final Duration MAX_CHALLENGE_TTL = Duration.ofMinutes(15);

    public TwoFactorProperties {
        if (issuer == null || issuer.isBlank() || issuer.contains(":")) {
            throw new IllegalArgumentException("tripmate.identity.two-factor.issuer is required and must not contain ':'");
        }
        Objects.requireNonNull(challengeTtl, "tripmate.identity.two-factor.challenge-ttl is required");
        if (challengeTtl.isNegative() || challengeTtl.isZero() || challengeTtl.compareTo(MAX_CHALLENGE_TTL) > 0) {
            throw new IllegalArgumentException("tripmate.identity.two-factor.challenge-ttl must be between 0 and 15m");
        }
        if (challengeMaxAttempts < 1 || challengeMaxAttempts > 10) {
            throw new IllegalArgumentException("tripmate.identity.two-factor.challenge-max-attempts must be 1..10");
        }
    }
}
