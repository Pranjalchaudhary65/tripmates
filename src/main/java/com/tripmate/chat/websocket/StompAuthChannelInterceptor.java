package com.tripmate.chat.websocket;

import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.SessionService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.RetryLaterException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.regex.Pattern;

/**
 * The socket's whole authorization, frame by frame. Deny by default, like
 * the HTTP filter chain:
 *
 * <ul>
 *   <li><b>CONNECT</b> must carry {@code Authorization: Bearer <access token>}
 *       as a STOMP header (a browser cannot put headers on the WebSocket
 *       handshake itself, which is why the handshake route is open and this
 *       is where the check happens). The token goes through the same decoder
 *       as every HTTP request, its session must still be alive, and its
 *       account active.</li>
 *   <li><b>SUBSCRIBE</b> is allowed to exactly one destination,
 *       {@code /user/queue/chat}: the caller's own queue. Spring resolves it
 *       to this connection's private queue, so a client cannot name anyone
 *       else's, and a raw {@code /queue/...} or {@code /topic/...} name is
 *       refused outright. Which conversations reach that queue is decided
 *       server-side when a message is pushed, never by what a client asks
 *       for.</li>
 *   <li><b>SEND</b> is allowed only to
 *       {@code /app/conversations/{uuid}/typing}. Everything else, a
 *       {@code /user/...} destination above all (it would deliver straight
 *       into another user's queue), is refused. Messages themselves are sent
 *       over HTTP.</li>
 *   <li>Every frame after CONNECT needs an authenticated connection whose
 *       token has not expired, and is charged to the connection's frame
 *       budget.</li>
 * </ul>
 *
 * <p>A refusal throws; Spring answers with an ERROR frame (worded by
 * {@link ChatStompErrorHandler}, which says nothing about why) and closes
 * the connection.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    public static final String CHAT_QUEUE = "/user/queue/chat";

    public static final String AUTHORIZATION = "Authorization";

    private static final String BEARER = "Bearer ";

    private static final Pattern TYPING = Pattern.compile(
            "/app/conversations/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/typing");

    private final JwtDecoder accessTokenDecoder;

    private final SessionService sessions;

    private final AccountService accounts;

    private final ChatConnections connections;

    private final Clock clock;

    public StompAuthChannelInterceptor(JwtDecoder accessTokenDecoder, SessionService sessions,
                                       AccountService accounts, ChatConnections connections, Clock clock) {
        this.accessTokenDecoder = accessTokenDecoder;
        this.sessions = sessions;
        this.accounts = accounts;
        this.connections = connections;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            ChatPrincipal principal = authenticate(accessor);
            accessor.setUser(principal);
            connections.authenticated(accessor.getSessionId(), principal);
            return message;
        }
        if (command == StompCommand.DISCONNECT) {
            return message;
        }
        if (!(accessor.getUser() instanceof ChatPrincipal principal)
                || !principal.expiresAt().isAfter(clock.instant())) {
            throw refused();
        }
        if (!connections.tryConsumeFrame(accessor.getSessionId())) {
            throw refused();
        }
        String destination = accessor.getDestination();
        switch (command) {
            case SUBSCRIBE -> {
                if (!CHAT_QUEUE.equals(destination)) {
                    throw refused();
                }
            }
            case SEND -> {
                if (destination == null || !TYPING.matcher(destination).matches()) {
                    throw refused();
                }
            }
            case UNSUBSCRIBE -> {
            }
            default -> throw refused();
        }
        return message;
    }

    private ChatPrincipal authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            throw refused();
        }
        try {
            Jwt jwt = accessTokenDecoder.decode(header.substring(BEARER.length()).strip());
            SessionPrincipal caller = SessionPrincipal.of(jwt);
            if (!sessions.isLive(caller.sessionId())) {
                throw refused();
            }
            accounts.requireActive(caller);
            return new ChatPrincipal(caller.userId(), caller.sessionId(), jwt.getExpiresAt());
        } catch (JwtException | AuthenticationException | RetryLaterException | IllegalArgumentException e) {
            throw refused();
        }
    }

    private static MessageDeliveryException refused() {
        return new MessageDeliveryException("Refused");
    }
}
