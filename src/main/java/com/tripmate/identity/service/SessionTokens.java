package com.tripmate.identity.service;

import com.tripmate.identity.entity.Role;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.token.AccessToken;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * What a login or a refresh hands back to the client.
 *
 * @param refreshToken  the new refresh cookie value, or null when the cookie
 *                      the browser holds stays as it is (a refresh that lost
 *                      a race inside the grace window)
 * @param refreshMaxAge how long the refresh cookie should live: the family's remaining lifetime
 */
public record SessionTokens(UUID userId, Role role, VerificationTier verificationTier, AccessToken accessToken,
                            String refreshToken, Duration refreshMaxAge) {

    public SessionTokens {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(verificationTier, "verificationTier");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshMaxAge, "refreshMaxAge");
    }

    /** Both tokens are credentials and stay out of logs. */
    @Override
    public String toString() {
        return "SessionTokens[userId=" + userId + ", role=" + role + ", rotated=" + (refreshToken != null) + "]";
    }
}
