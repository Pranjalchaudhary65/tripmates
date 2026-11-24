package com.tripmate.chat.websocket;

import com.tripmate.chat.service.ChatProperties;
import com.tripmate.identity.service.SessionService;
import com.tripmate.platform.error.RetryLaterException;
import com.tripmate.platform.ratelimit.RateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every open chat socket on this instance, and the rules that close one.
 *
 * <p>An access token is checked once, at STOMP CONNECT, but a socket can
 * stay open for hours. Without this, signing out, "revoke all devices" or a
 * detected token theft would end every HTTP request at once and leave an
 * open socket still receiving messages. So, every {@code sweep-interval}:
 * a socket whose token has expired is closed (the client reconnects with a
 * fresh one), a socket whose session has ended is closed, and a socket that
 * never authenticated within {@code connect-timeout} is closed. If the
 * session store cannot be asked, the socket is closed too: fail closed, as
 * for HTTP.
 *
 * <p>Each connection also gets its own frame budget, counted here in memory
 * since a connection lives on one instance: a client flooding SUBSCRIBE or
 * typing frames is cut off without touching Redis or the database.
 */
@Component
public class ChatConnections {

    private static final Logger log = LoggerFactory.getLogger(ChatConnections.class);

    private final Map<String, Connection> open = new ConcurrentHashMap<>();

    private final SessionService sessions;

    private final ChatProperties properties;

    private final Clock clock;

    public ChatConnections(SessionService sessions, ChatProperties properties, Clock clock) {
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    /** Wraps the STOMP handler so every socket is tracked from open to close. */
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {

            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                open.put(session.getId(), new Connection(session, clock.instant(),
                        new FrameBudget(properties.socket().frameLimit())));
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                open.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    void authenticated(String socketId, ChatPrincipal principal) {
        Connection connection = open.get(socketId);
        if (connection != null) {
            connection.principal = principal;
        }
    }

    /** Takes one frame from the connection's budget; false when it is spent or the connection is unknown. */
    boolean tryConsumeFrame(String socketId) {
        Connection connection = open.get(socketId);
        return connection != null && connection.budget.tryConsume(System.nanoTime());
    }

    /** How many sockets are open on this instance; for tests and health. */
    public int openCount() {
        return open.size();
    }

    @Scheduled(fixedDelayString = "${tripmate.chat.socket.sweep-interval}",
            initialDelayString = "${tripmate.chat.socket.sweep-interval}")
    public void sweep() {
        Instant now = clock.instant();
        Instant connectDeadline = now.minus(properties.socket().connectTimeout());
        Map<UUID, Boolean> liveBySession = new HashMap<>();
        for (Connection connection : open.values()) {
            ChatPrincipal principal = connection.principal;
            if (principal == null) {
                if (connection.openedAt.isBefore(connectDeadline)) {
                    close(connection, "never authenticated");
                }
                continue;
            }
            if (!principal.expiresAt().isAfter(now)) {
                close(connection, "access token expired");
                continue;
            }
            boolean live;
            try {
                live = liveBySession.computeIfAbsent(principal.sessionId(), sessions::isLive);
            } catch (RetryLaterException unavailable) {
                live = false;
            }
            if (!live) {
                close(connection, "session ended");
            }
        }
    }

    private void close(Connection connection, String reason) {
        open.remove(connection.session.getId());
        try {
            connection.session.close(CloseStatus.POLICY_VIOLATION.withReason("Reconnect"));
            log.debug("Closed chat socket: {}", reason);
        } catch (IOException e) {
            log.debug("Chat socket was already gone while closing it: {}", reason);
        }
    }

    private static final class Connection {

        private final WebSocketSession session;

        private final Instant openedAt;

        private final FrameBudget budget;

        private volatile ChatPrincipal principal;

        private Connection(WebSocketSession session, Instant openedAt, FrameBudget budget) {
            this.session = session;
            this.openedAt = openedAt;
            this.budget = budget;
        }
    }

    /** A token bucket refilled continuously, like the Redis-backed limits, for one connection only. */
    static final class FrameBudget {

        private final long capacity;

        private final double tokensPerNano;

        private double tokens;

        private long lastRefillNanos;

        private boolean started;

        FrameBudget(RateLimit limit) {
            this.capacity = limit.capacity();
            this.tokensPerNano = (double) limit.capacity() / limit.period().toNanos();
            this.tokens = limit.capacity();
        }

        synchronized boolean tryConsume(long nowNanos) {
            if (started) {
                tokens = Math.min(capacity, tokens + (nowNanos - lastRefillNanos) * tokensPerNano);
            }
            started = true;
            lastRefillNanos = nowNanos;
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }
    }
}
