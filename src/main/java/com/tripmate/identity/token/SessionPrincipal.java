package com.tripmate.identity.token;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is calling, taken from an access token that has already passed every
 * check in {@link AccessTokenDecoderConfig}. Controllers act on these IDs
 * only, never on an ID from the request path or body, for anything that
 * belongs to the caller.
 */
public record SessionPrincipal(UUID userId, UUID sessionId) {

    public SessionPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    public static SessionPrincipal of(Jwt jwt) {
        return new SessionPrincipal(UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(AccessTokenClaims.SESSION_ID)));
    }
}
