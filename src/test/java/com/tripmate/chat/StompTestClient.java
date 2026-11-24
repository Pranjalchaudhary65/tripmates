package com.tripmate.chat;

import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A real STOMP client over a real WebSocket to the test server, keeping
 * every frame the server pushes and every ERROR frame it sends.
 */
public final class StompTestClient {

    public static final Duration WAIT = Duration.ofSeconds(5);

    private static final WebSocketStompClient CLIENT = client();

    private StompTestClient() {
    }

    private static WebSocketStompClient client() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter());
        return client;
    }

    /**
     * Opens a socket and sends CONNECT with the token (none when null).
     * The future fails when the server refuses the CONNECT or the handshake.
     */
    public static CompletableFuture<Socket> connect(int port, String accessToken, String origin) {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        StompHeaders connect = new StompHeaders();
        if (accessToken != null) {
            connect.add("Authorization", "Bearer " + accessToken);
        }
        Socket socket = new Socket();
        return CLIENT.connectAsync(URI.create("ws://localhost:" + port + "/api/v1/ws"), handshake, connect, socket)
                .thenApply(session -> {
                    socket.session = session;
                    return socket;
                });
    }

    /**
     * Connected and subscribed to the caller's own chat queue, returned once
     * the broker has registered the subscription, so a push sent straight
     * after cannot race it.
     */
    public static Socket subscribed(int port, String accessToken, String userId, SimpUserRegistry users,
                                    SimpleBrokerMessageHandler broker) {
        Socket socket;
        try {
            socket = connect(port, accessToken, null).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not open a chat socket", e);
        }
        int before = registeredQueues(userId, users, broker);
        socket.subscribe("/user/queue/chat");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (registeredQueues(userId, users, broker) <= before) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The broker never registered the subscription");
            }
            Thread.onSpinWait();
        }
        return socket;
    }

    /** How many of the user's sessions have their private chat queue registered with the broker. */
    private static int registeredQueues(String userId, SimpUserRegistry users, SimpleBrokerMessageHandler broker) {
        SimpUser user = users.getUser(userId);
        if (user == null) {
            return 0;
        }
        int registered = 0;
        for (SimpSession session : user.getSessions()) {
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setDestination("/queue/chat-user" + session.getId());
            if (!broker.getSubscriptionRegistry()
                    .findSubscriptions(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()))
                    .isEmpty()) {
                registered++;
            }
        }
        return registered;
    }

    public static final class Socket extends StompSessionHandlerAdapter {

        private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        private final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        private volatile StompSession session;

        public void subscribe(String destination) {
            session.subscribe(destination, this);
        }

        public void sendTyping(String conversationId) {
            session.send("/app/conversations/" + conversationId + "/typing", new byte[0]);
        }

        public void send(String destination) {
            session.send(destination, new byte[0]);
        }

        /** The next pushed event's JSON, or null if none arrives within {@code wait}. */
        public String nextEvent(Duration wait) throws InterruptedException {
            return events.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        public String nextError(Duration wait) throws InterruptedException {
            return errors.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        public boolean isConnected() {
            return session != null && session.isConnected();
        }

        /** Waits for the server to close the connection; true once it has. */
        public boolean awaitClosed(Duration wait) throws InterruptedException {
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                if (!isConnected()) {
                    return true;
                }
                Thread.sleep(25);
            }
            return !isConnected();
        }

        public void disconnect() {
            if (isConnected()) {
                session.disconnect();
            }
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String text = new String((byte[]) payload, StandardCharsets.UTF_8);
            if (headers.getFirst("message") != null && headers.getSubscription() == null) {
                errors.add(headers.getFirst("message"));
            } else {
                events.add(text);
            }
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            errors.add("exception: " + exception.getClass().getSimpleName());
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.add("transport: " + exception.getClass().getSimpleName());
        }
    }
}
