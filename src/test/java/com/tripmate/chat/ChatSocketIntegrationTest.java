package com.tripmate.chat;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.chat.ChatApi.Pair;
import com.tripmate.chat.StompTestClient.Socket;
import com.tripmate.chat.websocket.ChatConnections;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import com.tripmate.trips.Traveller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chat socket end to end: a real WebSocket, real STOMP frames, the
 * real interceptor and broker. Each refusal is checked from the client's
 * side, as an attacker would see it.
 */
@IntegrationTest
class ChatSocketIntegrationTest {

    private static final Duration QUIET = Duration.ofMillis(500);

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ChatConnections connections;

    @Autowired
    private SimpUserRegistry users;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    private final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        sockets.forEach(Socket::disconnect);
        clock.reset();
    }

    @Test
    void aConnectWithoutAValidTokenIsRefused() {
        String real = Traveller.signUp(port, mailpit).token();
        String tamperedSignature = real.substring(0, real.length() - 4)
                + (real.endsWith("AAAA") ? "BBBB" : "AAAA");

        assertConnectRefused(null);
        assertConnectRefused("not-a-token");
        assertConnectRefused("eyJhbGciOiJub25lIn0.eyJzdWIiOiJub2JvZHkifQ.");
        assertConnectRefused(tamperedSignature);
    }

    @Test
    void aConnectWithTheTokenOfAnEndedSessionIsRefused() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        assertThat(traveller.http().post("/api/v1/auth/logout", "Authorization", "Bearer " + traveller.token())
                .statusCode()).isEqualTo(204);

        assertConnectRefused(traveller.token());
    }

    @Test
    void aHandshakeFromAForeignOriginIsRefused() {
        Traveller traveller = Traveller.signUp(port, mailpit);

        assertThatThrownBy(() -> StompTestClient.connect(port, traveller.token(), "https://evil.example")
                .get(StompTestClient.WAIT.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void aMessageReachesBothPeopleInTheConversationAndNobodyElse() throws Exception {
        Pair pair = Pair.accepted(port, mailpit);
        Traveller outsider = Traveller.signUp(port, mailpit);
        Socket host = subscribed(pair.host());
        Socket companion = subscribed(pair.companion());
        Socket stranger = subscribed(outsider);

        pair.companionChat().say(pair.companion().token(), pair.conversationId(), "Are we still on for Hampi?");

        String toHost = host.nextEvent(StompTestClient.WAIT);
        assertThat(toHost).isNotNull();
        assertThat(JsonPath.<String>read(toHost, "$.type")).isEqualTo("MESSAGE");
        assertThat(JsonPath.<String>read(toHost, "$.conversationId")).isEqualTo(pair.conversationId());
        assertThat(JsonPath.<String>read(toHost, "$.message.body")).isEqualTo("Are we still on for Hampi?");
        assertThat(JsonPath.<Boolean>read(toHost, "$.message.mine")).isFalse();
        assertThat(JsonPath.<Integer>read(toHost, "$.unreadCount")).isEqualTo(1);
        assertThat(toHost).doesNotContain(pair.companion().userId(), pair.host().userId());
        String toCompanion = companion.nextEvent(StompTestClient.WAIT);
        assertThat(JsonPath.<Boolean>read(toCompanion, "$.message.mine")).isTrue();
        assertThat(stranger.nextEvent(QUIET)).as("someone outside the conversation").isNull();
    }

    @Test
    void readingInOneTabTellsTheReadersOtherTabs() throws Exception {
        Pair pair = Pair.accepted(port, mailpit);
        pair.hostChat().say(pair.host().token(), pair.conversationId(), "one");
        Socket companion = subscribed(pair.companion());

        pair.companionChat().markRead(pair.companion().token(), pair.conversationId(), 1);

        String event = companion.nextEvent(StompTestClient.WAIT);
        assertThat(JsonPath.<String>read(event, "$.type")).isEqualTo("READ");
        assertThat(JsonPath.<Integer>read(event, "$.unreadCount")).isZero();
    }

    @Test
    void typingReachesOnlyTheOtherPersonAndIsDroppedForAConversationTheSenderIsNotIn() throws Exception {
        Pair pair = Pair.accepted(port, mailpit);
        Pair other = Pair.accepted(port, mailpit);
        Socket host = subscribed(pair.host());
        Socket companion = subscribed(pair.companion());
        Socket otherHost = subscribed(other.host());

        companion.sendTyping(pair.conversationId());
        String typing = host.nextEvent(StompTestClient.WAIT);
        assertThat(JsonPath.<String>read(typing, "$.type")).isEqualTo("TYPING");
        assertThat(JsonPath.<String>read(typing, "$.conversationId")).isEqualTo(pair.conversationId());
        assertThat(companion.nextEvent(QUIET)).as("not echoed to the typist").isNull();

        companion.sendTyping(other.conversationId());
        assertThat(otherHost.nextEvent(QUIET)).as("typing into someone else's conversation").isNull();
        assertThat(companion.isConnected()).as("dropped, not answered").isTrue();
    }

    @Test
    void subscribingToAnythingButTheCallersOwnQueueIsRefusedAndClosesTheSocket() throws Exception {
        Pair pair = Pair.accepted(port, mailpit);
        for (String destination : List.of("/topic/conversations", "/queue/chat",
                "/queue/chat-user" + "abc", "/user/" + pair.host().userId() + "/queue/chat", "/user/queue/other")) {
            Socket socket = connected(pair.companion());
            socket.subscribe(destination);

            assertThat(socket.nextError(StompTestClient.WAIT)).as(destination).isEqualTo("Not allowed");
            assertThat(socket.awaitClosed(StompTestClient.WAIT)).as(destination).isTrue();
        }
    }

    @Test
    void sendingStraightIntoAnotherUsersQueueIsRefused() throws Exception {
        Pair pair = Pair.accepted(port, mailpit);
        Socket host = subscribed(pair.host());
        Socket companion = connected(pair.companion());

        companion.send("/user/" + pair.host().userId() + "/queue/chat");

        assertThat(companion.nextError(StompTestClient.WAIT)).isEqualTo("Not allowed");
        assertThat(companion.awaitClosed(StompTestClient.WAIT)).isTrue();
        assertThat(host.nextEvent(QUIET)).isNull();
    }

    @Test
    void aClientFloodingFramesIsCutOff() throws Exception {
        Pair pair = Pair.accepted(port, mailpit);
        Socket companion = connected(pair.companion());

        for (int i = 0; i < 40 && companion.isConnected(); i++) {
            companion.sendTyping(pair.conversationId());
        }

        assertThat(companion.nextError(StompTestClient.WAIT)).isEqualTo("Not allowed");
        assertThat(companion.awaitClosed(StompTestClient.WAIT)).isTrue();
    }

    @Test
    void theSweepClosesASocketOnceItsSessionEnds() throws Exception {
        Traveller traveller = Traveller.signUp(port, mailpit);
        Socket socket = subscribed(traveller);

        traveller.http().post("/api/v1/auth/logout", "Authorization", "Bearer " + traveller.token());
        connections.sweep();

        assertThat(socket.awaitClosed(StompTestClient.WAIT)).isTrue();
    }

    @Test
    void theSweepClosesASocketOnceItsTokenExpiresAndLeavesFreshOnesOpen() throws Exception {
        Traveller expiring = Traveller.signUp(port, mailpit);
        Socket socket = subscribed(expiring);

        connections.sweep();
        assertThat(socket.isConnected()).as("token still valid").isTrue();

        clock.advance(Duration.ofMinutes(16));
        connections.sweep();

        assertThat(socket.awaitClosed(StompTestClient.WAIT)).isTrue();
    }

    private void assertConnectRefused(String token) {
        assertThatThrownBy(() -> StompTestClient.connect(port, token, null)
                .get(StompTestClient.WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("token %s", token)
                .isInstanceOf(ExecutionException.class);
    }

    private Socket connected(Traveller traveller) throws Exception {
        Socket socket = StompTestClient.connect(port, traveller.token(), null)
                .get(StompTestClient.WAIT.toSeconds(), TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    private Socket subscribed(Traveller traveller) {
        Socket socket = StompTestClient.subscribed(port, traveller.token(), traveller.userId(), users, broker);
        sockets.add(socket);
        return socket;
    }
}
