package com.tripmate.identity.token;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A signed access token and the times it was issued and expires, at the JWT's one-second precision. */
public record AccessToken(String value, Instant issuedAt, Instant expiresAt) {

    public AccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Seconds from issue to expiry: the OAuth-style {@code expires_in} a client schedules its refresh by. */
    public long expiresInSeconds() {
        return Duration.between(issuedAt, expiresAt).toSeconds();
    }

    /** The token is a bearer credential; it never goes into a log line. */
    @Override
    public String toString() {
        return "AccessToken[expiresAt=" + expiresAt + "]";
    }
}
