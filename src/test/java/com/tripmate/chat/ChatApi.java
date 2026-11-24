package com.tripmate.chat;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.LocalHttp;
import com.tripmate.trips.TripApi;
import com.tripmate.trips.Traveller;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls to the chat routes, and a host with an accepted companion to call them as. */
public final class ChatApi {

    public static final String CONVERSATIONS = "/api/v1/conversations";

    private final LocalHttp http;

    public ChatApi(LocalHttp http) {
        this.http = http;
    }

    public static String messageJson(UUID clientMessageId, String body) {
        return """
                {"clientMessageId":"%s","body":%s}""".formatted(clientMessageId, quote(body));
    }

    /** A JSON string literal for any text, control characters included. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(c -> {
            if (c == '"' || c == '\\') {
                out.append('\\').append((char) c);
            } else if (c < 0x20) {
                out.append("\\u%04x".formatted(c));
            } else {
                out.appendCodePoint(c);
            }
        });
        return out.append('"').toString();
    }

    public HttpResponse<String> inbox(String token) {
        return http.get(CONVERSATIONS, bearer(token));
    }

    public HttpResponse<String> conversation(String token, String conversationId) {
        return http.get(CONVERSATIONS + "/" + conversationId, bearer(token));
    }

    public HttpResponse<String> history(String token, String conversationId, String query) {
        return http.get(CONVERSATIONS + "/" + conversationId + "/messages" + (query.isEmpty() ? "" : "?" + query),
                bearer(token));
    }

    public HttpResponse<String> send(String token, String conversationId, UUID clientMessageId, String body) {
        return http.postJson(CONVERSATIONS + "/" + conversationId + "/messages", messageJson(clientMessageId, body),
                bearer(token));
    }

    /** Sends a message and returns the stored response body; fails the test if it is refused. */
    public String say(String token, String conversationId, String body) {
        HttpResponse<String> response = send(token, conversationId, UUID.randomUUID(), body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return response.body();
    }

    public HttpResponse<String> markRead(String token, String conversationId, long upToSeq) {
        return http.postJson(CONVERSATIONS + "/" + conversationId + "/read", """
                {"upToSeq":%d}""".formatted(upToSeq), bearer(token));
    }

    public HttpResponse<String> icebreakers(String token, String conversationId) {
        return http.get(CONVERSATIONS + "/" + conversationId + "/icebreakers", bearer(token));
    }

    public HttpResponse<String> contact(String token, String conversationId) {
        return http.get(CONVERSATIONS + "/" + conversationId + "/contact", bearer(token));
    }

    /** The ID of the caller's only conversation; fails unless there is exactly one. */
    public String onlyConversationId(String token) {
        HttpResponse<String> response = inbox(token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<String> ids = JsonPath.read(response.body(), "$.items[*].id");
        assertThat(ids).as(response.body()).hasSize(1);
        return ids.getFirst();
    }

    private static String[] bearer(String token) {
        return new String[] {"Authorization", "Bearer " + token};
    }

    /**
     * A host whose trip accepted one companion, so the two share one
     * conversation.
     */
    public record Pair(Traveller host, Traveller companion, String tripId, String conversationId) {

        public static Pair accepted(int port, com.tripmate.support.MailpitClient mailpit) {
            Traveller host = Traveller.signUp(port, mailpit);
            Traveller companion = Traveller.signUp(port, mailpit);
            String tripId = host.trips().open(host.token(), TripApi.tripJson(TripApi.uniquePlace(), 3));
            String requestId = companion.trips().join(companion.token(), tripId);
            HttpResponse<String> accepted = host.trips().accept(host.token(), requestId);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
            String conversationId = new ChatApi(host.http()).onlyConversationId(host.token());
            return new Pair(host, companion, tripId, conversationId);
        }

        public ChatApi hostChat() {
            return new ChatApi(host.http());
        }

        public ChatApi companionChat() {
            return new ChatApi(companion.http());
        }
    }
}
