package com.tripmate.trips;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.TripApi.Place;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class JoinRequestIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private Traveller host;

    private Traveller requester;

    private Place place;

    @BeforeEach
    void setUp() {
        host = Traveller.signUp(port, mailpit);
        requester = Traveller.signUp(port, mailpit);
        place = TripApi.uniquePlace();
    }

    @Test
    void aRequestWaitsForTheHostAndShowsOnBothSidesWithoutAnyoneElsesId() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 2));

        HttpResponse<String> sent = requester.trips().requestToJoin(requester.token(), tripId);

        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(sent.body(), "$.status")).isEqualTo("REQUESTED");
        assertThat(JsonPath.<String>read(sent.body(), "$.tripId")).isEqualTo(tripId);
        String requestId = JsonPath.read(sent.body(), "$.id");

        HttpResponse<String> outgoing = requester.trips().outgoing(requester.token());
        assertThat(JsonPath.<String>read(outgoing.body(), "$.items[0].id")).isEqualTo(requestId);
        assertThat(JsonPath.<String>read(outgoing.body(), "$.items[0].trip.title")).isEqualTo("Hills and chai");
        assertThat(JsonPath.<String>read(outgoing.body(), "$.items[0].host.firstName")).isEqualTo("Asha");
        assertThat(outgoing.body()).doesNotContain(host.userId(), host.email());

        HttpResponse<String> incoming = host.trips().incoming(host.token(), "");
        assertThat(JsonPath.<String>read(incoming.body(), "$.items[0].id")).isEqualTo(requestId);
        assertThat(JsonPath.<String>read(incoming.body(), "$.items[0].status")).isEqualTo("REQUESTED");
        assertThat(JsonPath.<String>read(incoming.body(), "$.items[0].requester.verificationTier"))
                .isEqualTo("EMAIL");
        assertThat(incoming.body()).doesNotContain(requester.userId(), requester.email(), "Verma");

        HttpResponse<String> browsed = requester.trips().browseNear(requester.token(), place, "");
        assertThat(JsonPath.<String>read(browsed.body(), "$.items[0].myRequest.id")).isEqualTo(requestId);
        assertThat(JsonPath.<String>read(browsed.body(), "$.items[0].myRequest.status")).isEqualTo("REQUESTED");
        assertThat(JsonPath.<Integer>read(host.trips().detail(host.token(), tripId).body(),
                "$.trip.pendingRequests")).isEqualTo(1);
    }

    @Test
    void acceptingTakesAPlaceAndTheLastPlaceFillsTheTrip() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 2));
        Traveller second = Traveller.signUp(port, mailpit);
        Traveller third = Traveller.signUp(port, mailpit);
        String first = requester.trips().join(requester.token(), tripId);
        String secondRequest = second.trips().join(second.token(), tripId);
        String thirdRequest = third.trips().join(third.token(), tripId);

        HttpResponse<String> accepted = host.trips().accept(host.token(), first);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(accepted.body(), "$.status")).isEqualTo("ACCEPTED");
        assertThat(JsonPath.<String>read(accepted.body(), "$.trip.status")).isEqualTo("OPEN");
        assertThat(JsonPath.<Integer>read(accepted.body(), "$.trip.filledSlots")).isEqualTo(1);

        HttpResponse<String> last = host.trips().accept(host.token(), secondRequest);
        assertThat(JsonPath.<String>read(last.body(), "$.trip.status")).isEqualTo("FULL");
        assertThat(JsonPath.<Integer>read(last.body(), "$.trip.filledSlots")).isEqualTo(2);

        HttpResponse<String> overflow = host.trips().accept(host.token(), thirdRequest);
        assertThat(overflow.statusCode()).isEqualTo(409);
        assertThat(code(overflow)).isEqualTo("trip_full");
        Traveller late = Traveller.signUp(port, mailpit);
        assertThat(code(late.trips().requestToJoin(late.token(), tripId))).isEqualTo("trip_full");
        assertThat(JsonPath.<List<String>>read(late.trips().browseNear(late.token(), place, "").body(),
                "$.items")).isEmpty();
        assertThat(late.trips().detail(late.token(), tripId).statusCode()).as("a full trip can still be read")
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from trip_members where trip_id = ?::uuid "
                + "and status = 'ACCEPTED'", Integer.class, tripId)).isEqualTo(2);
        assertThat(JsonPath.<String>read(requester.trips().outgoing(requester.token()).body(),
                "$.items[0].status")).isEqualTo("ACCEPTED");
    }

    @Test
    void aRejectionIsFinal() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 2));
        String requestId = requester.trips().join(requester.token(), tripId);

        HttpResponse<String> rejected = host.trips().reject(host.token(), requestId);

        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(rejected.body(), "$.status")).isEqualTo("REJECTED");
        assertThat(JsonPath.<Integer>read(rejected.body(), "$.trip.filledSlots")).isZero();
        assertThat(code(requester.trips().requestToJoin(requester.token(), tripId))).isEqualTo("join_request_exists");
        assertThat(code(host.trips().accept(host.token(), requestId))).isEqualTo("join_request_decided");
        assertThat(code(host.trips().reject(host.token(), requestId))).isEqualTo("join_request_decided");
        assertThat(JsonPath.<List<String>>read(host.trips().incoming(host.token(), "status=REQUESTED").body(),
                "$.items")).isEmpty();
        assertThat(JsonPath.<List<String>>read(host.trips().incoming(host.token(), "status=REJECTED").body(),
                "$.items[*].id")).containsExactly(requestId);
    }

    @Test
    void aSecondRequestForTheSameTripIsRefused() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 2));
        requester.trips().join(requester.token(), tripId);

        HttpResponse<String> again = requester.trips().requestToJoin(requester.token(), tripId);

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("join_request_exists");
    }

    @Test
    void aHostCannotAskToJoinTheirOwnTrip() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 2));

        HttpResponse<String> own = host.trips().requestToJoin(host.token(), tripId);

        assertThat(own.statusCode()).isEqualTo(409);
        assertThat(code(own)).isEqualTo("own_trip");
    }

    @Test
    void onlyTheTripsHostCanAnswerARequestAndEveryoneElseGetsNotFound() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 2));
        String requestId = requester.trips().join(requester.token(), tripId);
        Traveller stranger = Traveller.signUp(port, mailpit);

        for (Traveller notHost : List.of(requester, stranger)) {
            for (HttpResponse<String> response : List.of(notHost.trips().accept(notHost.token(), requestId),
                    notHost.trips().reject(notHost.token(), requestId))) {
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(code(response)).isEqualTo("not_found");
            }
        }
        assertThat(code(host.trips().accept(host.token(), UUID.randomUUID().toString()))).isEqualTo("not_found");
        assertThat(JsonPath.<List<String>>read(stranger.trips().incoming(stranger.token(), "tripId=" + tripId).body(),
                "$.items")).as("another trip's ID only narrows the stranger's own inbox").isEmpty();
        assertThat(jdbc.queryForObject("select status from trip_members where id = ?::uuid", String.class,
                requestId)).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("select filled_slots from trips where id = ?::uuid", Integer.class, tripId))
                .isZero();
    }

    @Test
    void aTripThatHasStartedTakesNoRequestsAndNoAccepts() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 2));
        String requestId = requester.trips().join(requester.token(), tripId);
        jdbc.update("update trips set start_date = ?, end_date = ? where id = ?::uuid",
                TripApi.today().minusDays(1), TripApi.today().plusDays(1), UUID.fromString(tripId));
        Traveller late = Traveller.signUp(port, mailpit);

        assertThat(code(late.trips().requestToJoin(late.token(), tripId))).isEqualTo("trip_closed");
        assertThat(code(host.trips().accept(host.token(), requestId))).isEqualTo("trip_closed");
        assertThat(host.trips().reject(host.token(), requestId).statusCode()).as("a request can still be turned down")
                .isEqualTo(200);
    }

    @Test
    void anAccountCannotHaveMoreWaitingRequestsThanTheCap() {
        Traveller otherHost = Traveller.signUp(port, mailpit);
        for (int i = 0; i < 5; i++) {
            Traveller tripHost = i < 3 ? host : otherHost;
            requester.trips().join(requester.token(), tripHost.trips().open(tripHost.token(),
                    TripApi.tripJson(place, 1)));
        }
        String sixth = otherHost.trips().open(otherHost.token(), TripApi.tripJson(place, 1));

        HttpResponse<String> refused = requester.trips().requestToJoin(requester.token(), sixth);

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("too_many_pending_requests");
        String answered = JsonPath.read(host.trips().incoming(host.token(), "").body(), "$.items[0].id");
        host.trips().reject(host.token(), answered);
        assertThat(requester.trips().requestToJoin(requester.token(), sixth).statusCode())
                .as("an answered request frees a place").isEqualTo(201);
    }

    @Test
    void aSuspendedRequesterLeavesTheInboxAndCannotBeAccepted() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 2));
        String requestId = requester.trips().join(requester.token(), tripId);
        jdbc.update("update users set account_status = 'SUSPENDED' where id = ?::uuid", requester.userId());

        assertThat(JsonPath.<List<String>>read(host.trips().incoming(host.token(), "").body(), "$.items")).isEmpty();
        HttpResponse<String> accepted = host.trips().accept(host.token(), requestId);
        assertThat(accepted.statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select filled_slots from trips where id = ?::uuid", Integer.class, tripId))
                .isZero();
    }

    @Test
    void aMalformedIdOrStatusIsABadRequest() {
        assertThat(host.trips().accept(host.token(), "not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(host.trips().detail(host.token(), "not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(host.trips().incoming(host.token(), "status=MAYBE").statusCode()).isEqualTo(400);
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }
}
