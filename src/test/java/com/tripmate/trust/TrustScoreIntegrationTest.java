package com.tripmate.trust;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.chat.entity.FlagType;
import com.tripmate.chat.service.ChatTrustEvents;
import com.tripmate.identity.VerificationApi;
import com.tripmate.meetup.service.MeetupTrustEvents;
import com.tripmate.platform.outbox.DeliveredEvent;
import com.tripmate.platform.outbox.OutboxRelay;
import com.tripmate.safety.SafetyApi;
import com.tripmate.settlement.SettlementApi.Crew;
import com.tripmate.settlement.service.SettlementTrustEvents;
import com.tripmate.settlement.service.SettlementTrustEvents.OverdueRole;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import com.tripmate.trips.Traveller;
import com.tripmate.trips.TripApi;
import com.tripmate.trust.service.TrustInputHandler;
import com.tripmate.trust.service.TrustScoreJob;
import com.tripmate.vibe.VibeApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trust score end to end: published inputs reach it through the outbox
 * relay, the job computes it off the request path, its owner sees how it
 * was reached, safety actions never touch it, and matching follows it at
 * once.
 */
@IntegrationTest
class TrustScoreIntegrationTest {

    private static final String TRUST_SCORE = "/api/v1/users/me/trust-score";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TrustScoreJob job;

    @Autowired
    private TrustInputHandler handler;

    @Autowired
    private MeetupTrustEvents meetupEvents;

    @Autowired
    private SettlementTrustEvents settlementEvents;

    @Autowired
    private ChatTrustEvents chatEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void aNewAccountIsScoredOffTheRequestPathAndSeesItsOwnBreakdown() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        assertThat(JsonPath.<Boolean>read(myScore(traveller), "$.pending")).as("before the job runs").isTrue();

        settle();

        String body = myScore(traveller);
        assertThat(JsonPath.<Boolean>read(body, "$.pending")).isFalse();
        assertThat(JsonPath.<Double>read(body, "$.score")).isEqualTo(53.76);
        assertThat(JsonPath.<String>read(body, "$.breakdown.verification.tier")).isEqualTo("EMAIL");
        assertThat(JsonPath.<Double>read(body, "$.breakdown.peerRating.value")).isEqualTo(62.5);
        assertThat(JsonPath.<Double>read(body, "$.breakdown.completion.value")).isEqualTo(50.0);
        assertThat(JsonPath.<Double>read(body, "$.breakdown.reliability.value")).isEqualTo(100.0);
        assertThat(cachedScore(traveller)).isEqualByComparingTo("53.76");
        assertThat(snapshots(traveller)).isEqualTo(1);

        settle();
        assertThat(snapshots(traveller)).as("nothing changed, so no new snapshot").isEqualTo(1);
    }

    @Test
    void eachPublishedBehaviourInputLowersReliabilityAndIsListed() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        UUID user = UUID.fromString(traveller.userId());
        inTransaction(status -> {
            meetupEvents.noShow(user, UUID.randomUUID());
            settlementEvents.settlementOverdue(user, UUID.randomUUID(), OverdueRole.PAYER);
            chatEvents.messageFlagged(user, UUID.randomUUID(), FlagType.MONEY_REQUEST, new BigDecimal("0.97"));
        });

        settle();

        String body = myScore(traveller);
        assertThat(JsonPath.<Double>read(body, "$.breakdown.reliability.value")).isEqualTo(55.0);
        assertThat(JsonPath.<List<String>>read(body, "$.breakdown.reliability.deductions[*].type"))
                .containsExactlyInAnyOrder("MEETUP_NO_SHOW", "SETTLEMENT_OVERDUE", "CHAT_MESSAGE_FLAGGED");
        assertThat(JsonPath.<List<String>>read(body, "$.breakdown.reliability.deductions[*].detail"))
                .contains("PAYER", "MONEY_REQUEST");
        assertThat(JsonPath.<Double>read(body, "$.score")).isEqualTo(44.76);
    }

    @Test
    void aRedeliveredEventIsRecordedOnce() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        UUID user = UUID.fromString(traveller.userId());
        inTransaction(status -> meetupEvents.lateConvenienceCancel(user, UUID.randomUUID()));
        Map<String, Object> row = jdbc.queryForMap("""
                select id, aggregate_type, aggregate_id, event_type, payload::text as payload, created_at
                from outbox_events where aggregate_id = ? and event_type = 'MEETUP_LATE_CANCEL'""", user);
        DeliveredEvent event = new DeliveredEvent((UUID) row.get("id"), (String) row.get("aggregate_type"), user,
                (String) row.get("event_type"), (String) row.get("payload"),
                ((java.sql.Timestamp) row.get("created_at")).toInstant());

        inTransaction(status -> handler.handle(event));
        inTransaction(status -> handler.handle(event));
        relay.drain();

        assertThat(jdbc.queryForObject("select count(*) from trust_score_events where user_id = ? "
                + "and event_type = 'MEETUP_LATE_CANCEL'", Long.class, user)).isEqualTo(1);
    }

    @Test
    void safetyActionsNeverReachAnyonesScore() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 2);
        settle();
        Map<String, BigDecimal> before = scoresOf(crew);
        long eventsBefore = trustEventsOf(crew);

        HttpResponse<String> sos = new SafetyApi(crew.host().http()).sos(crew.host().token(), crew.tripId(),
                UUID.randomUUID(), null, null);
        assertThat(sos.statusCode()).as(sos.body()).isEqualTo(201);
        jdbc.update("update trip_members set status = 'CANCELLED', cancellation_type = 'SAFETY' "
                + "where trip_id = ?::uuid and user_id = ?::uuid", crew.tripId(), crew.companion(1).userId());
        jdbc.update("update trips set start_date = current_date - 5, end_date = current_date - 2 where id = ?::uuid",
                crew.tripId());
        jdbc.update("update trust_score_state set dirty_since = now() where user_id::text in (?, ?, ?)",
                crew.everyoneIds().toArray());
        settle();

        assertThat(trustEventsOf(crew)).as("no trust input from an SOS or a safety release").isEqualTo(eventsBefore);
        assertThat(cachedScore(crew.companion(1))).as("stepping back for safety costs nothing")
                .isEqualByComparingTo(before.get(crew.companion(1).userId()));
        assertThat(JsonPath.<Integer>read(myScore(crew.companion(1)), "$.breakdown.completion.finishedTrips"))
                .as("and does not count as a finished trip either").isZero();
        assertThat(JsonPath.<Integer>read(myScore(crew.host()), "$.breakdown.completion.finishedTrips"))
                .as("the host and the companion who stayed finished the trip").isEqualTo(1);
        assertThat(cachedScore(crew.host())).isGreaterThan(before.get(crew.host().userId()));
    }

    @Test
    void matchingFollowsATrustScoreChangeAtOnceAndTheCachedScoreStaysRight() {
        Traveller host = Traveller.signUp(port, mailpit);
        Traveller viewer = Traveller.signUp(port, mailpit);
        for (Traveller traveller : List.of(host, viewer)) {
            assertThat(new VibeApi(traveller.http()).submit(traveller.token(), VibeApi.FIRST_OPTIONS).statusCode())
                    .isEqualTo(200);
        }
        settle();
        String tripId = host.trips().open(host.token(), TripApi.tripJson(TripApi.uniquePlace(), 2));
        String scored = viewer.trips().detail(viewer.token(), tripId).body();
        int matchScore = JsonPath.read(scored, "$.match.score");

        HttpResponse<String> limit = new VibeApi(host.http()).dealBreakers(host.token(), """
                {"smoking":"EITHER","drinking":"EITHER","genderPreference":"ANY","minTrustScore":60}""");
        assertThat(limit.statusCode()).as(limit.body()).isEqualTo(200);
        String blocked = viewer.trips().detail(viewer.token(), tripId).body();
        assertThat(JsonPath.<String>read(blocked, "$.match.status")).isEqualTo("BLOCKED");
        assertThat(JsonPath.<List<String>>read(blocked, "$.match.blockers[*].rule")).containsExactly("HOST_TRUST_MINIMUM");

        new VerificationApi(viewer.http(), mailpit).verifyPhone(viewer.email(), viewer.token());
        settle();

        assertThat(cachedScore(viewer)).isEqualByComparingTo("61.26");
        String again = viewer.trips().detail(viewer.token(), tripId).body();
        assertThat(JsonPath.<String>read(again, "$.match.status")).isEqualTo("SCORED");
        assertThat(JsonPath.<Integer>read(again, "$.match.score")).isEqualTo(matchScore);
    }

    @Test
    void anAccountThatWasNeverQueuedStillGetsAScore() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        settle();
        jdbc.update("delete from trust_score_state where user_id = ?::uuid", traveller.userId());
        jdbc.update("update users set trust_score = 0 where id = ?::uuid", traveller.userId());

        settle();

        assertThat(cachedScore(traveller)).as("an existing user is not left at 0, hidden by every filter")
                .isEqualByComparingTo("53.76");
    }

    @Test
    void scoresAreRefreshedAsDeductionsFade() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        UUID user = UUID.fromString(traveller.userId());
        inTransaction(status -> meetupEvents.noShow(user, UUID.randomUUID()));
        settle();
        assertThat(JsonPath.<Double>read(myScore(traveller), "$.breakdown.reliability.value")).isEqualTo(80.0);

        clock.advance(Duration.ofDays(400));
        job.sweep();
        clock.reset();

        assertThat(JsonPath.<Double>read(myScore(traveller), "$.breakdown.reliability.value"))
                .as("a no-show fades to half and stays").isEqualTo(90.0);
        assertThat(snapshots(traveller)).isEqualTo(2);
    }

    @Test
    void scoreHistoryCannotBeRewritten() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        settle();
        for (String statement : List.of(
                "update trust_score_snapshots set score = 99 where user_id = '%s'".formatted(traveller.userId()),
                "delete from trust_score_snapshots where user_id = '%s'".formatted(traveller.userId()),
                "update trust_score_events set detail = 'X' where user_id = '%s'".formatted(traveller.userId()),
                "delete from trust_score_events where user_id = '%s'".formatted(traveller.userId()),
                "truncate trust_score_snapshots", "truncate trust_score_events")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute(statement)).as(statement)
                    .hasStackTraceContaining("append-only");
        }
    }

    private void settle() {
        relay.drain();
        job.sweep();
    }

    private void inTransaction(Consumer<org.springframework.transaction.TransactionStatus> work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(work);
    }

    private String myScore(Traveller traveller) {
        HttpResponse<String> response = traveller.http().get(TRUST_SCORE, "Authorization", "Bearer " + traveller.token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private BigDecimal cachedScore(Traveller traveller) {
        return jdbc.queryForObject("select trust_score from users where id = ?::uuid", BigDecimal.class,
                traveller.userId());
    }

    private long snapshots(Traveller traveller) {
        return jdbc.queryForObject("select count(*) from trust_score_snapshots where user_id = ?::uuid", Long.class,
                traveller.userId());
    }

    private Map<String, BigDecimal> scoresOf(Crew crew) {
        return crew.everyone().stream().collect(java.util.stream.Collectors.toMap(Traveller::userId,
                this::cachedScore));
    }

    private long trustEventsOf(Crew crew) {
        return jdbc.queryForObject("select count(*) from trust_score_events where user_id::text in (?, ?, ?)",
                Long.class, crew.everyoneIds().toArray());
    }
}
