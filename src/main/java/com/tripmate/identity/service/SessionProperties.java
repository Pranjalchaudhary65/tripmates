package com.tripmate.identity.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param lifetime    absolute lifetime of a refresh token family, from sign-in; rotation never extends it
 * @param reuseGrace  how long after a rotation the token it replaced is still accepted, for an access token only
 */
@ConfigurationProperties("tripmate.identity.session")
public record SessionProperties(Duration lifetime, Duration reuseGrace) {

    private static final Duration MAX_REUSE_GRACE = Duration.ofMinutes(1);

    public SessionProperties {
        Objects.requireNonNull(lifetime, "tripmate.identity.session.lifetime is required");
        Objects.requireNonNull(reuseGrace, "tripmate.identity.session.reuse-grace is required");
        if (lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("tripmate.identity.session.lifetime must be positive");
        }
        if (reuseGrace.isNegative() || reuseGrace.compareTo(MAX_REUSE_GRACE) > 0) {
            throw new IllegalArgumentException("tripmate.identity.session.reuse-grace must be between 0 and 1m");
        }
    }
}
