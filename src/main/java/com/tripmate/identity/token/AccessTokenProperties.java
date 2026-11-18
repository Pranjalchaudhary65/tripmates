package com.tripmate.identity.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param issuer             {@code iss} claim written and required
 * @param audience           {@code aud} claim written and required
 * @param ttl                lifetime of one access token
 * @param clockSkew          tolerance on {@code exp} and {@code iat} for clocks of several instances drifting apart
 * @param signingKey         base64 HMAC key new tokens are signed with; no default
 * @param previousSigningKey base64 key still accepted for verification during a rotation; blank when not rotating
 */
@ConfigurationProperties("tripmate.identity.access-token")
public record AccessTokenProperties(String issuer, String audience, Duration ttl, Duration clockSkew,
                                    String signingKey, String previousSigningKey) {

    private static final Duration MAX_TTL = Duration.ofHours(1);

    public AccessTokenProperties {
        requireText(issuer, "issuer");
        requireText(audience, "audience");
        Objects.requireNonNull(ttl, "tripmate.identity.access-token.ttl is required");
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("tripmate.identity.access-token.ttl must be between 0 and 1h");
        }
        Objects.requireNonNull(clockSkew, "tripmate.identity.access-token.clock-skew is required");
        if (clockSkew.isNegative() || clockSkew.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException("tripmate.identity.access-token.clock-skew must be in [0, ttl)");
        }
        requireText(signingKey, "signing-key (JWT_SIGNING_KEY)");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tripmate.identity.access-token." + name + " is required");
        }
    }

    /** Keys stay out of any log line or startup failure report that prints this record. */
    @Override
    public String toString() {
        return "AccessTokenProperties[issuer=" + issuer + ", audience=" + audience + ", ttl=" + ttl + "]";
    }
}
