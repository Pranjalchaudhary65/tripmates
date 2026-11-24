package com.tripmate.chat.websocket;

import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Who a socket connection belongs to, taken from the access token its STOMP
 * CONNECT carried. The name is the user ID, which is what Spring's user
 * destinations route on: a push to {@code /user/{id}/queue/chat} reaches
 * exactly this user's connections.
 *
 * @param expiresAt the access token's expiry; the connection is closed then,
 *                  and the client reconnects with a fresh token
 */
public record ChatPrincipal(UUID userId, UUID sessionId, Instant expiresAt) implements Principal {

    public ChatPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
