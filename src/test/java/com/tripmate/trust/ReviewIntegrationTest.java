package com.tripmate.trust;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.platform.outbox.OutboxRelay;
import com.tripmate.settlement.SettlementApi.Crew;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.Traveller;
import com.tripmate.trust.service.ReviewWindowJob;
import com.tripmate.trust.service.TrustScoreJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Double-blind reviews: only people who travelled to the end together, one
 * review per pair per trip, hidden from everyone (the trust score included)
 * until both have written or the window closes.
 */
@IntegrationTest
class ReviewIntegrationTest {

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

    @Autowired
    private ReviewWindowJob windowJob;

    @Test
    void aReviewStaysHiddenFromEveryoneUntilTheOtherPersonReviewsBack() {
        Crew crew = ended(1);
        Traveller host = crew.host();
        Traveller companion = crew.companion(0);

        HttpResponse<String> first = review(host, crew, companion, 5, "Always on time, great company");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(first.body(), "$.people[0].status")).isEqualTo("WAITING_FOR_THEM");

        String companionView = screen(companion, crew).body();
        assertThat(JsonPath.<String>read(companionView, "$.people[0].status")).isEqualTo("TO_WRITE");
        assertThat(JsonPath.<Object>read(companionView, "$.people[0].theirs")).isNull();
        assertThat(companionView).doesNotContain("great company");
        assertThat(mine(companion)).doesNotContain("great company");
        settle();
        assertThat(visibleReviewsInScore(companion)).as("a hidden review never reaches the score").isZero();

        HttpResponse<String> second = review(companion, crew, host, 2, null);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);

        assertThat(JsonPath.<String>read(second.body(), "$.people[0].status")).isEqualTo("REVEALED");
        assertThat(JsonPath.<Integer>read(second.body(), "$.people[0].theirs.rating")).isEqualTo(5);
        assertThat(JsonPath.<String>read(second.body(), "$.people[0].theirs.comment"))
                .isEqualTo("Always on time, great company");
        assertThat(JsonPath.<Integer>read(screen(host, crew).body(), "$.people[0].theirs.rating")).isEqualTo(2);
        assertThat(JsonPath.<List<Integer>>read(mine(companion), "$.received[*].rating")).containsExactly(5);
        settle();
        assertThat(visibleReviewsInScore(companion)).isEqualTo(1);
        assertThat(visibleReviewsInScore(host)).isEqualTo(1);
    }

    @Test
    void whenTheWindowClosesWhateverWasWrittenBecomesVisible() {
        Crew crew = ended(1);
        assertThat(review(crew.host(), crew, crew.companion(0), 4, "Fine").statusCode()).isEqualTo(201);
        jdbc.update("update trips set start_date = current_date - 22, end_date = current_date - 20 "
                + "where id = ?::uuid", crew.tripId());

        windowJob.run();

        String companionView = screen(crew.companion(0), crew).body();
        assertThat(JsonPath.<String>read(companionView, "$.window.status")).isEqualTo("CLOSED");
        assertThat(JsonPath.<Integer>read(companionView, "$.people[0].theirs.rating")).isEqualTo(4);
        assertThat(JsonPath.<String>read(companionView, "$.people[0].status")).isEqualTo("NOT_WRITTEN");
        assertProblem(review(crew.companion(0), crew, crew.host(), 1, null), 409, "review_window_closed");
    }

    @Test
    void reviewsFollowTheRulesOfWhoWhenAndHowOften() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        Traveller host = crew.host();
        Traveller companion = crew.companion(0);
        assertProblem(review(host, crew, companion, 5, null), 409, "review_window_not_open");
        jdbc.update("update trips set start_date = current_date - 5, end_date = current_date - 2 where id = ?::uuid",
                crew.tripId());

        assertProblem(review(host, crew, host, 5, null), 400, "not_a_travel_companion");
        assertProblem(review(host, crew, Traveller.signUp(port, mailpit), 5, null), 400, "not_a_travel_companion");
        assertProblem(review(host, crew, companion, 0, null), 400, "invalid_rating");
        assertProblem(review(host, crew, companion, 6, null), 400, "invalid_rating");
        assertProblem(review(host, crew, companion, 5, "x".repeat(501)), 400, "invalid_comment");

        HttpResponse<String> written = review(host, crew, companion, 5, "Great‮ trip");
        assertThat(written.statusCode()).as(written.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(written.body(), "$.people[0].mine.comment")).isEqualTo("Great trip");
        assertProblem(review(host, crew, companion, 1, null), 409, "already_reviewed");

        assertThatThrownBy(() -> jdbc.update("update reviews set rating = 1 where trip_id = ?::uuid", crew.tripId()))
                .hasStackTraceContaining("cannot be edited");
        assertThatThrownBy(() -> jdbc.update("delete from reviews where trip_id = ?::uuid", crew.tripId()))
                .hasStackTraceContaining("cannot be removed");
    }

    @Test
    void onlyTravellersStillOnTheTripReviewOrAreReviewed() {
        Crew crew = ended(2);
        Traveller steppedBack = crew.companion(1);
        jdbc.update("update trip_members set status = 'CANCELLED', cancellation_type = 'SAFETY' "
                + "where trip_id = ?::uuid and user_id = ?::uuid", crew.tripId(), steppedBack.userId());
        Traveller stranger = Traveller.signUp(port, mailpit);

        assertProblem(screen(steppedBack, crew), 404, "not_found");
        assertProblem(screen(stranger, crew), 404, "not_found");
        assertProblem(review(steppedBack, crew, crew.host(), 1, null), 404, "not_found");
        assertProblem(review(crew.host(), crew, steppedBack, 1, null), 400, "not_a_travel_companion");
        assertThat(JsonPath.<List<String>>read(screen(crew.host(), crew).body(), "$.people[*].userId"))
                .containsExactly(crew.companion(0).userId());
        assertThat(JsonPath.<List<String>>read(screen(crew.companion(0), crew).body(), "$.people[*].userId"))
                .containsExactly(crew.host().userId());
    }

    @Test
    void theInboxListsTripsWaitingForTheCallersReviews() {
        Crew crew = ended(2);

        String inbox = mine(crew.companion(0));
        assertThat(JsonPath.<List<String>>read(inbox, "$.toReview[*].trip.id")).containsExactly(crew.tripId());
        assertThat(JsonPath.<Integer>read(inbox, "$.toReview[0].waiting")).isEqualTo(2);

        review(crew.companion(0), crew, crew.host(), 5, null);
        review(crew.companion(0), crew, crew.companion(1), 4, null);
        assertThat(JsonPath.<List<String>>read(mine(crew.companion(0)), "$.toReview")).isEmpty();
    }

    @Test
    void twoReviewsSubmittedAtTheSameMomentAreBothRevealed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                Crew crew = ended(1);
                CountDownLatch start = new CountDownLatch(1);
                List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
                for (Traveller[] pair : List.of(new Traveller[] {crew.host(), crew.companion(0)},
                        new Traveller[] {crew.companion(0), crew.host()})) {
                    sent.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return review(pair[0], crew, pair[1], 5, null);
                    }, pool));
                }
                start.countDown();
                for (CompletableFuture<HttpResponse<String>> response : sent) {
                    assertThat(response.get().statusCode()).as(response.get().body()).isEqualTo(201);
                }
                assertThat(jdbc.queryForObject("select count(*) from reviews where trip_id = ?::uuid "
                        + "and visible_at is not null", Long.class, crew.tripId())).as("round %d", round).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** A locked crew whose trip ended two days ago, so its review window is open. */
    private Crew ended(int companions) {
        Crew crew = Crew.locked(port, mailpit, jdbc, companions);
        jdbc.update("update trips set start_date = current_date - 5, end_date = current_date - 2 where id = ?::uuid",
                crew.tripId());
        return crew;
    }

    private HttpResponse<String> review(Traveller from, Crew crew, Traveller about, int rating, String comment) {
        String json = comment == null
                ? "{\"revieweeId\":\"%s\",\"rating\":%d}".formatted(about.userId(), rating)
                : "{\"revieweeId\":\"%s\",\"rating\":%d,\"comment\":\"%s\"}".formatted(about.userId(), rating,
                comment.replace("‮", "\\u202E"));
        return from.http().postJson("/api/v1/trips/" + crew.tripId() + "/reviews", json,
                "Authorization", "Bearer " + from.token());
    }

    private HttpResponse<String> screen(Traveller who, Crew crew) {
        return who.http().get("/api/v1/trips/" + crew.tripId() + "/reviews", "Authorization", "Bearer " + who.token());
    }

    private String mine(Traveller who) {
        HttpResponse<String> response = who.http().get("/api/v1/users/me/reviews", "Authorization",
                "Bearer " + who.token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private void settle() {
        relay.drain();
        trustJob.sweep();
    }

    private int visibleReviewsInScore(Traveller who) {
        HttpResponse<String> response = who.http().get("/api/v1/users/me/trust-score", "Authorization",
                "Bearer " + who.token());
        return JsonPath.read(response.body(), "$.breakdown.peerRating.visibleReviews");
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"" + code + "\"");
    }
}
