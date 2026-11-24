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
class TripIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private Traveller host;

    private Traveller viewer;

    private Place place;

    @BeforeEach
    void setUp() {
        host = Traveller.signUp(port, mailpit);
        viewer = Traveller.signUp(port, mailpit);
        place = TripApi.uniquePlace();
    }

    @Test
    void openingATripReturnsItAsItsHostSeesIt() {
        HttpResponse<String> opened = host.trips().create(host.token(), TripApi.tripJson(place, 3));

        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(opened.body(), "$.trip.status")).isEqualTo("OPEN");
        assertThat(JsonPath.<Integer>read(opened.body(), "$.trip.totalSlots")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(opened.body(), "$.trip.filledSlots")).isZero();
        assertThat(JsonPath.<Boolean>read(opened.body(), "$.trip.hostedByMe")).isTrue();
        assertThat(JsonPath.<Integer>read(opened.body(), "$.trip.pendingRequests")).isZero();
        assertThat(JsonPath.<String>read(opened.body(), "$.trip.host.firstName")).isEqualTo("Asha");
        assertThat(JsonPath.<String>read(opened.body(), "$.trip.host.verificationTier")).isEqualTo("EMAIL");
        assertThat(JsonPath.<String>read(opened.body(), "$.description"))
                .isEqualTo("Slow days, early starts, shared rooms.");
        assertThat(opened.body()).doesNotContain(host.userId(), host.email(), "Verma", "hostUserId");
        String tripId = JsonPath.read(opened.body(), "$.trip.id");
        assertThat(jdbc.queryForObject("""
                select round(ST_Distance(destination_point,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)) from trips where id = ?::uuid""",
                Double.class, place.longitude(), place.latitude(), tripId)).isZero();
    }

    @Test
    void datesOutsideTheTripRulesAreRefused() {
        List<String> bad = List.of(
                TripApi.tripJson(place, 2, TripApi.today().minusDays(1), TripApi.today().plusDays(2), "MID"),
                TripApi.tripJson(place, 2, TripApi.today().plusDays(5), TripApi.today().plusDays(4), "MID"),
                TripApi.tripJson(place, 2, TripApi.today().plusDays(5), TripApi.today().plusDays(66), "MID"),
                TripApi.tripJson(place, 2, TripApi.today().plusDays(366), TripApi.today().plusDays(367), "MID"));
        for (String json : bad) {
            HttpResponse<String> response = host.trips().create(host.token(), json);
            assertThat(response.statusCode()).as(json).isEqualTo(400);
            assertThat(code(response)).isEqualTo("invalid_trip_dates");
        }
        HttpResponse<String> today = host.trips().create(host.token(),
                TripApi.tripJson(place, 2, TripApi.today(), TripApi.today().plusDays(60), "MID"));
        assertThat(today.statusCode()).as("starting today and 60 days long is fine: %s", today.body())
                .isEqualTo(201);
    }

    @Test
    void aMalformedTripIsRefusedFieldByField() {
        String valid = TripApi.tripJson(place, 2);
        List<String[]> cases = List.of(
                new String[] {valid.replace("Hills and chai", "Hi\\u202Eiahc"), "title"},
                new String[] {valid.replace("Hills and chai", "Hills\\u0007"), "title"},
                new String[] {valid.replace("\"totalSlots\":2", "\"totalSlots\":21"), "totalSlots"},
                new String[] {valid.replace("\"totalSlots\":2", "\"totalSlots\":0"), "totalSlots"},
                new String[] {valid.replace("\"radiusKm\":10", "\"radiusKm\":501"), "radiusKm"},
                new String[] {valid.replace("\"latitude\":" + place.latitude(), "\"latitude\":91"), "latitude"},
                new String[] {valid.replace("\"budgetBand\":\"MID\"", "\"budgetBand\":null"), "budgetBand"});
        for (String[] bad : cases) {
            HttpResponse<String> response = host.trips().create(host.token(), bad[0]);
            assertThat(response.statusCode()).as(bad[1]).isEqualTo(400);
            assertThat(JsonPath.<List<String>>read(response.body(), "$.errors[*].field")).as(bad[1])
                    .containsExactly(bad[1]);
        }
        HttpResponse<String> multiLine = host.trips().create(host.token(),
                valid.replace("shared rooms.", "shared rooms.\\nBring a torch."));
        assertThat(multiLine.statusCode()).as("line breaks in a description: %s", multiLine.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from trips where host_user_id = ?::uuid", Integer.class,
                host.userId())).isEqualTo(1);
    }

    @Test
    void browseFindsOpenTripsAroundAPointWithTheirDistanceButNotTheViewersOwn() {
        String near = host.trips().open(host.token(), TripApi.tripJson(place, 3));
        host.trips().open(host.token(), TripApi.tripJson(new Place(place.latitude() + 0.5, place.longitude()), 3));
        viewer.trips().open(viewer.token(), TripApi.tripJson(place, 2));

        HttpResponse<String> found = viewer.trips().browseNear(viewer.token(), place, "");

        assertThat(found.statusCode()).as(found.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(found.body(), "$.items[*].id")).containsExactly(near);
        assertThat(JsonPath.<Integer>read(found.body(), "$.totalItems")).isEqualTo(1);
        assertThat(JsonPath.<Double>read(found.body(), "$.items[0].distanceKm")).isLessThan(0.01);
        assertThat(JsonPath.<Boolean>read(found.body(), "$.items[0].hostedByMe")).isFalse();
        assertThat((Object) JsonPath.read(found.body(), "$.items[0].pendingRequests")).isNull();
        assertThat((Object) JsonPath.read(found.body(), "$.items[0].myRequest")).isNull();
        assertThat(found.body()).doesNotContain(host.userId(), host.email());

        HttpResponse<String> wider = viewer.trips().browse(viewer.token(), "latitude=%s&longitude=%s&radiusKm=80"
                .formatted(place.latitude(), place.longitude()));
        assertThat(JsonPath.<List<String>>read(wider.body(), "$.items[*].id")).hasSize(2).startsWith(near);
    }

    @Test
    void browseNarrowsByDatesAndBudgetAndPagesSoonestFirst() {
        String soon = host.trips().open(host.token(),
                TripApi.tripJson(place, 2, TripApi.today().plusDays(3), TripApi.today().plusDays(5), "SHOESTRING"));
        String later = host.trips().open(host.token(),
                TripApi.tripJson(place, 2, TripApi.today().plusDays(20), TripApi.today().plusDays(25), "LUXURY"));
        String latest = host.trips().open(host.token(),
                TripApi.tripJson(place, 2, TripApi.today().plusDays(40), TripApi.today().plusDays(42), "MID"));

        assertThat(ids(viewer.trips().browseNear(viewer.token(), place, ""))).containsExactly(soon, later, latest);
        assertThat(ids(viewer.trips().browseNear(viewer.token(), place,
                "from=" + TripApi.today().plusDays(10) + "&to=" + TripApi.today().plusDays(30))))
                .containsExactly(later);
        assertThat(ids(viewer.trips().browseNear(viewer.token(), place, "budget=SHOESTRING&budget=MID")))
                .containsExactly(soon, latest);

        HttpResponse<String> second = viewer.trips().browseNear(viewer.token(), place, "page=1&size=2");
        assertThat(ids(second)).containsExactly(latest);
        assertThat(JsonPath.<Integer>read(second.body(), "$.totalPages")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(second.body(), "$.size")).isEqualTo(2);
        HttpResponse<String> clamped = viewer.trips().browseNear(viewer.token(), place, "size=5000");
        assertThat(JsonPath.<Integer>read(clamped.body(), "$.size")).isEqualTo(50);
    }

    @Test
    void aBrowseFilterThatDoesNotAddUpIsRefused() {
        for (String query : List.of("latitude=10&longitude=20", "radiusKm=5", "latitude=10&longitude=20&radiusKm=0",
                "latitude=91&longitude=20&radiusKm=5", "latitude=10&longitude=20&radiusKm=1001",
                "latitude=NaN&longitude=20&radiusKm=5")) {
            HttpResponse<String> response = viewer.trips().browse(viewer.token(), query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(code(response)).as(query).isEqualTo("invalid_location_filter");
        }
        HttpResponse<String> backwards = viewer.trips().browse(viewer.token(),
                "from=" + TripApi.today().plusDays(9) + "&to=" + TripApi.today().plusDays(2));
        assertThat(backwards.statusCode()).isEqualTo(400);
        assertThat(code(backwards)).isEqualTo("invalid_date_filter");
        for (String query : List.of("budget=CHEAP", "from=tomorrow", "latitude=north&longitude=1&radiusKm=1")) {
            assertThat(viewer.trips().browse(viewer.token(), query).statusCode()).as(query).isEqualTo(400);
        }
    }

    @Test
    void aTripNoLongerListedIsHiddenFromEveryoneButTheHostAndPeopleOnIt() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 3));
        Traveller requester = Traveller.signUp(port, mailpit);
        requester.trips().join(requester.token(), tripId);
        jdbc.update("update trips set status = 'CANCELLED' where id = ?::uuid", tripId);

        HttpResponse<String> stranger = viewer.trips().detail(viewer.token(), tripId);
        HttpResponse<String> unknown = viewer.trips().detail(viewer.token(), UUID.randomUUID().toString());

        assertThat(stranger.statusCode()).isEqualTo(404);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(code(stranger)).isEqualTo(code(unknown)).isEqualTo("not_found");
        assertThat(JsonPath.<String>read(stranger.body(), "$.detail"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.detail"));
        assertThat(host.trips().detail(host.token(), tripId).statusCode()).isEqualTo(200);
        HttpResponse<String> asRequester = requester.trips().detail(requester.token(), tripId);
        assertThat(asRequester.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(asRequester.body(), "$.trip.myRequest.status")).isEqualTo("REQUESTED");
        assertThat(ids(viewer.trips().browseNear(viewer.token(), place, ""))).isEmpty();
        assertThat(code(viewer.trips().requestToJoin(viewer.token(), tripId))).isEqualTo("not_found");
    }

    @Test
    void aSuspendedHostsTripsDisappearForEveryoneElse() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 3));
        jdbc.update("update users set account_status = 'SUSPENDED' where id = ?::uuid", host.userId());

        assertThat(ids(viewer.trips().browseNear(viewer.token(), place, ""))).isEmpty();
        assertThat(viewer.trips().detail(viewer.token(), tripId).statusCode()).isEqualTo(404);
        HttpResponse<String> join = viewer.trips().requestToJoin(viewer.token(), tripId);
        assertThat(join.statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from trip_members where trip_id = ?::uuid", Integer.class,
                tripId)).isZero();
    }

    @Test
    void hostedListsTheCallersOwnTripsWithTheirWaitingRequests() {
        String tripId = host.trips().open(host.token(), TripApi.tripJson(place, 3));
        viewer.trips().join(viewer.token(), tripId);

        HttpResponse<String> hosted = host.trips().hosted(host.token());

        assertThat(hosted.statusCode()).isEqualTo(200);
        assertThat(ids(hosted)).containsExactly(tripId);
        assertThat(JsonPath.<Integer>read(hosted.body(), "$.items[0].pendingRequests")).isEqualTo(1);
        assertThat(ids(viewer.trips().hosted(viewer.token()))).isEmpty();
    }

    @Test
    void aHostCannotHaveMoreCurrentTripsThanTheCap() {
        for (int i = 0; i < 5; i++) {
            host.trips().open(host.token(), TripApi.tripJson(place, 1));
        }

        HttpResponse<String> sixth = host.trips().create(host.token(), TripApi.tripJson(place, 1));

        assertThat(sixth.statusCode()).isEqualTo(409);
        assertThat(code(sixth)).isEqualTo("too_many_trips");
        jdbc.update("update trips set status = 'CANCELLED' where id = ?::uuid",
                JsonPath.<String>read(host.trips().hosted(host.token()).body(), "$.items[0].id"));
        assertThat(host.trips().create(host.token(), TripApi.tripJson(place, 1)).statusCode())
                .as("a cancelled trip no longer counts").isEqualTo(201);
    }

    private static List<String> ids(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.items[*].id");
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }
}
