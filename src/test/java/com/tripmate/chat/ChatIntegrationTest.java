package com.tripmate.chat;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.chat.ChatApi.Pair;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.TripApi;
import com.tripmate.trips.Traveller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ChatIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void acceptingACompanionOpensOneConversationBothSidesSee() {
        Pair pair = Pair.accepted(port, mailpit);

        HttpResponse<String> asHost = pair.hostChat().conversation(pair.host().token(), pair.conversationId());
        HttpResponse<String> asCompanion = pair.companionChat().conversation(pair.companion().token(),
                pair.conversationId());

        assertThat(asHost.statusCode()).as(asHost.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(asHost.body(), "$.conversation.myRole")).isEqualTo("HOST");
        assertThat(JsonPath.<String>read(asHost.body(), "$.conversation.trip.id")).isEqualTo(pair.tripId());
        assertThat(JsonPath.<Boolean>read(asHost.body(), "$.conversation.canSend")).isTrue();
        assertThat(JsonPath.<Boolean>read(asHost.body(), "$.conversation.contactUnlocked")).isFalse();
        assertThat(JsonPath.<List<?>>read(asHost.body(), "$.messages")).isEmpty();
        assertThat(asCompanion.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(asCompanion.body(), "$.conversation.myRole")).isEqualTo("COMPANION");
        assertThat(asHost.body()).doesNotContain(pair.host().userId(), pair.companion().userId(),
                pair.companion().email());
        assertThat(asCompanion.body()).doesNotContain(pair.host().userId(), pair.host().email());
    }

    @Test
    void aRequestThatIsRejectedOrStillWaitingOpensNoConversation() {
        Traveller host = Traveller.signUp(port, mailpit);
        Traveller rejected = Traveller.signUp(port, mailpit);
        Traveller waiting = Traveller.signUp(port, mailpit);
        String tripId = host.trips().open(host.token(), TripApi.tripJson(TripApi.uniquePlace(), 3));
        host.trips().reject(host.token(), rejected.trips().join(rejected.token(), tripId));
        waiting.trips().join(waiting.token(), tripId);

        for (Traveller traveller : List.of(host, rejected, waiting)) {
            HttpResponse<String> inbox = new ChatApi(traveller.http()).inbox(traveller.token());
            assertThat(JsonPath.<Integer>read(inbox.body(), "$.totalItems")).isZero();
        }
    }

    @Test
    void anotherCompanionOnTheSameTripCannotReachTheConversation() {
        Pair pair = Pair.accepted(port, mailpit);
        Traveller second = Traveller.signUp(port, mailpit);
        pair.host().trips().accept(pair.host().token(), second.trips().join(second.token(), pair.tripId()));
        ChatApi secondChat = new ChatApi(second.http());
        String ownConversation = secondChat.onlyConversationId(second.token());
        assertThat(ownConversation).isNotEqualTo(pair.conversationId());

        for (HttpResponse<String> response : List.of(
                secondChat.conversation(second.token(), pair.conversationId()),
                secondChat.history(second.token(), pair.conversationId(), ""),
                secondChat.send(second.token(), pair.conversationId(), UUID.randomUUID(), "hello?"),
                secondChat.markRead(second.token(), pair.conversationId(), 1),
                secondChat.icebreakers(second.token(), pair.conversationId()),
                secondChat.contact(second.token(), pair.conversationId()))) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
            assertThat(response.body()).contains("\"not_found\"");
        }
        assertThat(JsonPath.<Integer>read(pair.hostChat().inbox(pair.host().token()).body(), "$.totalItems"))
                .isEqualTo(2);
    }

    @Test
    void bothSidesReadTheConversationInOrder() {
        Pair pair = Pair.accepted(port, mailpit);
        pair.hostChat().say(pair.host().token(), pair.conversationId(), "Hi! Slow trip or a packed one?");
        pair.companionChat().say(pair.companion().token(), pair.conversationId(), "Slow, with early starts.");

        HttpResponse<String> asHost = pair.hostChat().conversation(pair.host().token(), pair.conversationId());

        assertThat(JsonPath.<List<Integer>>read(asHost.body(), "$.messages[*].seq")).containsExactly(1, 2);
        assertThat(JsonPath.<List<Boolean>>read(asHost.body(), "$.messages[*].mine")).containsExactly(true, false);
        assertThat(JsonPath.<List<String>>read(asHost.body(), "$.messages[*].body"))
                .containsExactly("Hi! Slow trip or a packed one?", "Slow, with early starts.");
        assertThat(JsonPath.<List<String>>read(asHost.body(), "$.messages[*].kind")).containsOnly("USER");
    }

    @Test
    void theBodyIsStoredEncryptedAndBoundToItsOwnRow() {
        Pair pair = Pair.accepted(port, mailpit);
        pair.hostChat().say(pair.host().token(), pair.conversationId(), "Meet by the old lighthouse");
        pair.hostChat().say(pair.host().token(), pair.conversationId(), "Second message");

        byte[] stored = jdbc.queryForObject("select body_ciphertext from chat_messages where conversation_id = ?::uuid "
                + "and seq = 1", byte[].class, pair.conversationId());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("lighthouse");

        jdbc.update("""
                update chat_messages set body_ciphertext = (select body_ciphertext from chat_messages
                    where conversation_id = ?::uuid and seq = 1)
                where conversation_id = ?::uuid and seq = 2""", pair.conversationId(), pair.conversationId());
        HttpResponse<String> read = pair.companionChat().conversation(pair.companion().token(),
                pair.conversationId());

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(read.body(), "$.messages[0].body")).isEqualTo("Meet by the old lighthouse");
        assertThat(JsonPath.<Object>read(read.body(), "$.messages[1].body")).as("a moved ciphertext does not open")
                .isNull();
    }

    @Test
    void markupIsStrippedAndContactDetailsNeverReachTheDatabase() {
        Pair pair = Pair.accepted(port, mailpit);

        String sent = pair.companionChat().say(pair.companion().token(), pair.conversationId(),
                "<b>Hey</b> <script>alert(1)</script>call me on +91 98765 43210 or asha@example.com");

        String body = JsonPath.read(sent, "$.body");
        assertThat(body).isEqualTo("Hey call me on [contact hidden] or [contact hidden]");
        assertThat(JsonPath.<Boolean>read(sent, "$.redacted")).isTrue();
        assertThat(JsonPath.<String>read(sent, "$.flag")).isEqualTo("OFF_PLATFORM_PUSH");
        String seen = pair.hostChat().conversation(pair.host().token(), pair.conversationId()).body();
        assertThat(seen).doesNotContain("98765", "asha@example.com", "<b>", "script");
    }

    @Test
    void aRetriedSendIsStoredOnceAndAReusedIdWithOtherTextIsRefused() {
        Pair pair = Pair.accepted(port, mailpit);
        ChatApi chat = pair.hostChat();
        UUID clientId = UUID.randomUUID();

        HttpResponse<String> first = chat.send(pair.host().token(), pair.conversationId(), clientId, "Hello there");
        HttpResponse<String> retry = chat.send(pair.host().token(), pair.conversationId(), clientId, "Hello there");
        HttpResponse<String> reused = chat.send(pair.host().token(), pair.conversationId(), clientId, "Other text");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(retry.body(), "$.id")).isEqualTo(JsonPath.read(first.body(), "$.id"));
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(reused.body()).contains("\"message_id_reused\"");
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where conversation_id = ?::uuid",
                Integer.class, pair.conversationId())).isEqualTo(1);
    }

    @Test
    void emptyAndOverlongMessagesAreRefused() {
        Pair pair = Pair.accepted(port, mailpit);
        ChatApi chat = pair.hostChat();

        HttpResponse<String> onlyMarkup = chat.send(pair.host().token(), pair.conversationId(), UUID.randomUUID(),
                "<p> </p>​");
        HttpResponse<String> tooLong = chat.send(pair.host().token(), pair.conversationId(), UUID.randomUUID(),
                "a".repeat(2001));
        HttpResponse<String> overTheRawCap = chat.send(pair.host().token(), pair.conversationId(), UUID.randomUUID(),
                "a".repeat(4001));

        assertThat(onlyMarkup.statusCode()).isEqualTo(400);
        assertThat(onlyMarkup.body()).contains("\"empty_message\"");
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(tooLong.body()).contains("\"message_too_long\"");
        assertThat(overTheRawCap.statusCode()).isEqualTo(400);
    }

    @Test
    void unreadCountsFollowTheOtherSidesMessagesAndTheReadMarker() {
        Pair pair = Pair.accepted(port, mailpit);
        pair.hostChat().say(pair.host().token(), pair.conversationId(), "one");
        pair.hostChat().say(pair.host().token(), pair.conversationId(), "two");
        pair.hostChat().say(pair.host().token(), pair.conversationId(), "three");

        assertThat(unread(pair.companionChat(), pair.companion().token())).isEqualTo(3);
        assertThat(unread(pair.hostChat(), pair.host().token())).isZero();

        assertThat(pair.companionChat().markRead(pair.companion().token(), pair.conversationId(), 2).statusCode())
                .isEqualTo(204);
        assertThat(unread(pair.companionChat(), pair.companion().token())).isEqualTo(1);
        pair.companionChat().markRead(pair.companion().token(), pair.conversationId(), 1);
        assertThat(unread(pair.companionChat(), pair.companion().token())).as("never moves back").isEqualTo(1);

        pair.companionChat().say(pair.companion().token(), pair.conversationId(), "four");
        assertThat(unread(pair.companionChat(), pair.companion().token())).as("replying reads everything").isZero();
        String inbox = pair.hostChat().inbox(pair.host().token()).body();
        assertThat(JsonPath.<Integer>read(inbox, "$.items[0].unreadCount")).isEqualTo(1);
        assertThat(JsonPath.<String>read(inbox, "$.items[0].lastMessage.preview")).isEqualTo("four");
        assertThat(JsonPath.<Boolean>read(inbox, "$.items[0].lastMessage.mine")).isFalse();
    }

    @Test
    void historyPagesBackwardsBySeq() {
        Pair pair = Pair.accepted(port, mailpit);
        for (int i = 1; i <= 5; i++) {
            pair.hostChat().say(pair.host().token(), pair.conversationId(), "message " + i);
        }

        HttpResponse<String> page = pair.companionChat().history(pair.companion().token(), pair.conversationId(),
                "before=4&size=2");

        assertThat(JsonPath.<List<Integer>>read(page.body(), "$.items[*].seq")).containsExactly(2, 3);
        assertThat(JsonPath.<Boolean>read(page.body(), "$.hasOlder")).isTrue();
        HttpResponse<String> first = pair.companionChat().history(pair.companion().token(), pair.conversationId(),
                "before=2&size=2");
        assertThat(JsonPath.<List<Integer>>read(first.body(), "$.items[*].seq")).containsExactly(1);
        assertThat(JsonPath.<Boolean>read(first.body(), "$.hasOlder")).isFalse();
    }

    @Test
    void aMoneyRequestIsFlaggedQueuedAndFeedsTheTrustScore() {
        Pair pair = Pair.accepted(port, mailpit);

        String sent = pair.hostChat().say(pair.host().token(), pair.conversationId(),
                "Send me 2000 on gpay as advance to hold your seat");
        String messageId = JsonPath.read(sent, "$.id");

        assertThat(JsonPath.<String>read(sent, "$.flag")).isEqualTo("MONEY_REQUEST");
        String seenByCompanion = pair.companionChat().conversation(pair.companion().token(), pair.conversationId())
                .body();
        assertThat(JsonPath.<String>read(seenByCompanion, "$.messages[0].flag")).isEqualTo("MONEY_REQUEST");
        Map<String, Object> flag = jdbc.queryForMap("select flag_type, auto_action from moderation_flags "
                + "where chat_message_id = ?::uuid and flag_type = 'MONEY_REQUEST'", messageId);
        assertThat(flag.get("auto_action")).isEqualTo("TRUST_SCORE_PENALTY");
        String payload = jdbc.queryForObject("""
                select payload::text from outbox_events
                where event_type = 'CHAT_MESSAGE_FLAGGED' and aggregate_id = ?::uuid""", String.class,
                pair.host().userId());
        assertThat(payload).contains(messageId, "MONEY_REQUEST").doesNotContain("gpay", "2000");
    }

    @Test
    void aWeakFlagWarnsButDoesNotTouchTheTrustScore() {
        Pair pair = Pair.accepted(port, mailpit);

        String sent = pair.companionChat().say(pair.companion().token(), pair.conversationId(),
                "Do you use WhatsApp?");

        assertThat(JsonPath.<String>read(sent, "$.flag")).isEqualTo("OFF_PLATFORM_PUSH");
        assertThat(jdbc.queryForObject("select auto_action from moderation_flags where chat_message_id = ?::uuid",
                String.class, (String) JsonPath.read(sent, "$.id"))).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events
                where event_type = 'CHAT_MESSAGE_FLAGGED' and aggregate_id = ?::uuid""", Integer.class,
                pair.companion().userId())).isZero();
    }

    @Test
    void aClosedConversationOrAnInactiveOtherSideTakesNoNewMessages() {
        Pair closed = Pair.accepted(port, mailpit);
        closed.hostChat().say(closed.host().token(), closed.conversationId(), "before closing");
        jdbc.update("update conversations set status = 'CLOSED' where id = ?::uuid", closed.conversationId());

        HttpResponse<String> refused = closed.companionChat().send(closed.companion().token(),
                closed.conversationId(), UUID.randomUUID(), "after closing");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("\"conversation_closed\"");
        String history = closed.companionChat().conversation(closed.companion().token(), closed.conversationId())
                .body();
        assertThat(JsonPath.<Boolean>read(history, "$.conversation.canSend")).isFalse();
        assertThat(JsonPath.<String>read(history, "$.messages[0].body")).isEqualTo("before closing");

        Pair suspended = Pair.accepted(port, mailpit);
        jdbc.update("update users set account_status = 'SUSPENDED' where id = ?::uuid", suspended.host().userId());
        HttpResponse<String> toSuspended = suspended.companionChat().send(suspended.companion().token(),
                suspended.conversationId(), UUID.randomUUID(), "hello?");
        assertThat(toSuspended.statusCode()).isEqualTo(409);
        assertThat(toSuspended.body()).contains("\"conversation_closed\"");
    }

    @Test
    void contactDetailsStayLockedUntilTheTripLocks() {
        Pair pair = Pair.accepted(port, mailpit);

        HttpResponse<String> locked = pair.companionChat().contact(pair.companion().token(), pair.conversationId());
        assertThat(locked.statusCode()).isEqualTo(409);
        assertThat(locked.body()).contains("\"contact_locked\"").doesNotContain(pair.host().email());

        jdbc.update("update conversations set contact_unlocked_at = now() where id = ?::uuid",
                pair.conversationId());
        HttpResponse<String> unlocked = pair.companionChat().contact(pair.companion().token(), pair.conversationId());

        assertThat(unlocked.statusCode()).as(unlocked.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(unlocked.body(), "$.email")).isEqualToIgnoringCase(pair.host().email());
        assertThat(JsonPath.<Object>read(unlocked.body(), "$.phone")).as("no verified phone").isNull();
        HttpResponse<String> other = pair.hostChat().contact(pair.host().token(), pair.conversationId());
        assertThat(JsonPath.<String>read(other.body(), "$.email")).isEqualToIgnoringCase(pair.companion().email());
    }

    @Test
    void icebreakersAreThreeLinesAboutTheTripAndSendNothing() {
        Pair pair = Pair.accepted(port, mailpit);

        HttpResponse<String> response = pair.companionChat().icebreakers(pair.companion().token(),
                pair.conversationId());

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> suggestions = JsonPath.read(response.body(), "$.suggestions");
        assertThat(suggestions).hasSize(3);
        assertThat(suggestions.getFirst()).contains("Somewhere quiet");
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where conversation_id = ?::uuid",
                Integer.class, pair.conversationId())).isZero();
    }

    @Test
    void anIcebreakerThatWouldCarryContactDetailsIsDropped() {
        Pair pair = Pair.accepted(port, mailpit);
        jdbc.update("update trips set destination_name = 'Call 9876543210' where id = ?::uuid", pair.tripId());

        List<String> suggestions = JsonPath.read(pair.companionChat().icebreakers(pair.companion().token(),
                pair.conversationId()).body(), "$.suggestions");

        assertThat(suggestions).isNotEmpty().noneMatch(line -> line.contains("9876543210"));
    }

    private static int unread(ChatApi chat, String token) {
        return JsonPath.read(chat.inbox(token).body(), "$.items[0].unreadCount");
    }
}
