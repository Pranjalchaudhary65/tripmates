package com.tripmate.moderation;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.chat.ChatApi;
import com.tripmate.identity.VerificationApi;
import com.tripmate.platform.outbox.OutboxRelay;
import com.tripmate.settlement.SettlementApi.Crew;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.Traveller;
import com.tripmate.trips.TripApi;
import com.tripmate.trust.service.TrustScoreJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Blocking and reporting: only people met through a trip, a block hides two
 * people from each other everywhere, reports are sealed, and enough
 * different reporters suspend an account and penalise its vouchers.
 */
@IntegrationTest
class ModerationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TrustScoreJob trustJob;

    @Test
    void aBlockHidesTwoPeopleFromEachOtherUntilItIsLifted() {
        ChatApi.Pair pair = ChatApi.Pair.accepted(port, mailpit);
        Traveller host = pair.host();
        Traveller companion = pair.companion();
        TripApi.Place place = TripApi.uniquePlace();
        String otherTrip = host.trips().open(host.token(), TripApi.tripJson(place, 2));
        assertThat(browsed(companion, place)).contains(otherTrip);

        HttpResponse<String> blocked = post(companion, "/api/v1/blocks", "{\"matchId\":\"%s\"}"
                .formatted(matchId(pair)));
        assertThat(blocked.statusCode()).as(blocked.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(blocked.body(), "$.firstName")).isNotBlank();
        assertThat(blocked.body()).doesNotContain(host.userId());

        assertThat(browsed(companion, place)).doesNotContain(otherTrip);
        assertProblem(companion.trips().detail(companion.token(), otherTrip), 404, "not_found");
        assertProblem(companion.trips().requestToJoin(companion.token(), otherTrip), 404, "not_found");
        assertProblem(pair.hostChat().send(host.token(), pair.conversationId(), UUID.randomUUID(), "Hi"), 409,
                "conversation_closed");
        assertProblem(pair.companionChat().send(companion.token(), pair.conversationId(), UUID.randomUUID(), "Hi"),
                409, "conversation_closed");
        assertThat(post(companion, "/api/v1/blocks", "{\"matchId\":\"%s\"}".formatted(matchId(pair))).body())
                .as("blocking twice returns the same block").contains(JsonPath.<String>read(blocked.body(), "$.id"));

        String blockId = JsonPath.read(blocked.body(), "$.id");
        assertProblem(host.http().delete("/api/v1/blocks/" + blockId, bearer(host)), 404, "not_found");
        assertThat(companion.http().delete("/api/v1/blocks/" + blockId, bearer(companion)).statusCode())
                .isEqualTo(204);
        assertThat(JsonPath.<List<Object>>read(companion.http().get("/api/v1/blocks", bearer(companion)).body(),
                "$.items")).isEmpty();
        assertThat(browsed(companion, place)).contains(otherTrip);
        pair.companionChat().say(companion.token(), pair.conversationId(), "Sorry, wrong button");
    }

    @Test
    void onlySomeoneMetThroughATripCanBeBlockedOrReported() {
        ChatApi.Pair pair = ChatApi.Pair.accepted(port, mailpit);
        Traveller stranger = Traveller.signUp(port, mailpit);
        String byMatch = "{\"matchId\":\"%s\",\"reason\":\"HARASSMENT\"}".formatted(matchId(pair));

        assertProblem(post(stranger, "/api/v1/reports", byMatch), 404, "not_found");
        assertProblem(post(stranger, "/api/v1/blocks", "{\"matchId\":\"%s\"}".formatted(matchId(pair))), 404,
                "not_found");
        assertProblem(post(stranger, "/api/v1/reports", "{\"tripId\":\"%s\",\"userId\":\"%s\",\"reason\":\"OTHER\"}"
                .formatted(pair.tripId(), pair.host().userId())), 404, "not_found");
        assertProblem(post(pair.host(), "/api/v1/reports", ("{\"tripId\":\"%s\",\"userId\":\"%s\",\"reason\":"
                + "\"OTHER\"}").formatted(pair.tripId(), pair.companion().userId())), 404, "not_found");
        assertProblem(post(pair.host(), "/api/v1/reports", "{\"reason\":\"OTHER\"}"), 400, "invalid_target");
        assertProblem(post(pair.host(), "/api/v1/reports", ("{\"matchId\":\"%s\",\"tripId\":\"%s\","
                + "\"reason\":\"OTHER\"}").formatted(matchId(pair), pair.tripId())), 400, "invalid_target");
        assertProblem(post(pair.host(), "/api/v1/reports", "{\"matchId\":\"%s\",\"reason\":\"RUDE\"}"
                .formatted(matchId(pair))), 400, "invalid_reason");
        assertProblem(post(pair.host(), "/api/v1/reports", ("{\"matchId\":\"%s\",\"reason\":\"OTHER\",\"details\":"
                + "\"%s\"}").formatted(matchId(pair), "x".repeat(1001))), 400, "invalid_details");
        assertThat(jdbc.queryForObject("select count(*) from reports where reported_id = ?::uuid", Long.class,
                pair.host().userId())).isZero();
    }

    @Test
    void aReportIsSealedCountedOnceAndNeverEdited() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        String json = "{\"tripId\":\"%s\",\"userId\":\"%s\",\"reason\":\"SCAM_OR_MONEY_REQUEST\",\"details\":"
                + "\"Asked me to pay a deposit to his own UPI\"}";
        HttpResponse<String> report = post(crew.companion(0), "/api/v1/reports",
                json.formatted(crew.tripId(), crew.host().userId()));

        assertThat(report.statusCode()).as(report.body()).isEqualTo(201);
        assertThat(report.body()).doesNotContain(crew.host().userId());
        byte[] sealed = jdbc.queryForObject("select details_ciphertext from reports where id = ?::uuid",
                byte[].class, JsonPath.<String>read(report.body(), "$.id"));
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("deposit");
        assertProblem(post(crew.companion(0), "/api/v1/reports", json.formatted(crew.tripId(), crew.host().userId())),
                409, "already_reported");
        assertThatThrownBy(() -> jdbc.update("update reports set reason = 'OTHER' where reported_id = ?::uuid",
                crew.host().userId())).hasStackTraceContaining("cannot be edited");
        assertThatThrownBy(() -> jdbc.update("delete from reports where reported_id = ?::uuid",
                crew.host().userId())).hasStackTraceContaining("cannot be removed");
        assertThat(accountStatus(crew.host())).as("one report suspends nobody").isEqualTo("ACTIVE");
    }

    @Test
    void threeDifferentReportersSuspendTheAccountAndPenaliseItsVouchers() {
        Traveller host = Traveller.signUp(port, mailpit);
        Traveller voucher = Traveller.signUp(port, mailpit);
        VerificationApi verification = new VerificationApi(voucher.http(), mailpit);
        verification.reachSelfieMatch(voucher.email(), voucher.token());
        assertThat(verification.vouch(voucher.token(), host.userId()).statusCode()).isEqualTo(201);
        String tripId = host.trips().open(host.token(), TripApi.tripJson(TripApi.uniquePlace(), 3));
        List<Traveller> reporters = new ArrayList<>();
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Traveller companion = Traveller.signUp(port, mailpit);
            String requestId = companion.trips().join(companion.token(), tripId);
            assertThat(host.trips().accept(host.token(), requestId).statusCode()).isEqualTo(200);
            reporters.add(companion);
            matches.add(requestId);
        }

        for (int i = 0; i < 2; i++) {
            assertThat(post(reporters.get(i), "/api/v1/reports", "{\"matchId\":\"%s\",\"reason\":\"HARASSMENT\"}"
                    .formatted(matches.get(i))).statusCode()).isEqualTo(201);
        }
        assertThat(accountStatus(host)).isEqualTo("ACTIVE");
        HttpResponse<String> third = post(reporters.get(2), "/api/v1/reports",
                "{\"matchId\":\"%s\",\"reason\":\"UNSAFE_BEHAVIOUR\"}".formatted(matches.get(2)));
        assertThat(third.statusCode()).as(third.body()).isEqualTo(201);
        assertThat(third.body()).as("the reporter is not told the outcome").doesNotContain("SUSPENDED");

        assertThat(accountStatus(host)).isEqualTo("SUSPENDED");
        assertThat(host.http().get("/api/v1/users/me", bearer(host)).statusCode()).as("sessions ended").isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'VOUCH_PENALIZED' "
                + "and aggregate_id = ?::uuid", Long.class, voucher.userId())).isEqualTo(1);
        relay.drain();
        trustJob.sweep();
        String score = voucher.http().get("/api/v1/users/me/trust-score", bearer(voucher)).body();
        assertThat(JsonPath.<Integer>read(score, "$.breakdown.vouchPenalty.count")).isEqualTo(1);
    }

    private String matchId(ChatApi.Pair pair) {
        return jdbc.queryForObject("select id::text from trip_members where trip_id = ?::uuid", String.class,
                pair.tripId());
    }

    private List<String> browsed(Traveller who, TripApi.Place place) {
        HttpResponse<String> response = who.trips().browseNear(who.token(), place, "");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.items[*].id");
    }

    private String accountStatus(Traveller who) {
        return jdbc.queryForObject("select account_status from users where id = ?::uuid", String.class, who.userId());
    }

    private static HttpResponse<String> post(Traveller who, String path, String json) {
        return who.http().postJson(path, json, bearer(who));
    }

    private static String[] bearer(Traveller who) {
        return new String[] {"Authorization", "Bearer " + who.token()};
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"" + code + "\"");
    }
}
