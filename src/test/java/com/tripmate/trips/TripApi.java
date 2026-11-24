package com.tripmate.trips;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.LocalHttp;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls to the trip and join request routes, and trip bodies to send them. */
public final class TripApi {

    public static final String TRIPS = "/api/v1/trips";

    public static final String HOSTED = "/api/v1/trips/hosted";

    public static final String INCOMING = "/api/v1/join-requests/incoming";

    public static final String OUTGOING = "/api/v1/join-requests/outgoing";

    private final LocalHttp http;

    public TripApi(LocalHttp http) {
        this.http = http;
    }

    /** Today where the platform's trip rules run. */
    public static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    /**
     * A place nobody else's test uses, so a browse around it with a small
     * radius finds only the trips this test put there.
     */
    public static Place uniquePlace() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new Place(random.nextDouble(-60, 60), random.nextDouble(-170, 170));
    }

    /** A valid trip at the place, a week out, three nights long. */
    public static String tripJson(Place place, int slots) {
        return tripJson(place, slots, today().plusDays(7), today().plusDays(10), "MID");
    }

    public static String tripJson(Place place, int slots, LocalDate start, LocalDate end, String budget) {
        return """
                {"title":"Hills and chai","description":"Slow days, early starts, shared rooms.",
                 "destinationName":"Somewhere quiet","latitude":%s,"longitude":%s,"radiusKm":10,
                 "startDate":"%s","endDate":"%s","totalSlots":%d,"budgetBand":"%s"}"""
                .formatted(place.latitude(), place.longitude(), start, end, slots, budget);
    }

    public HttpResponse<String> create(String accessToken, String json) {
        return http.postJson(TRIPS, json, bearer(accessToken));
    }

    /** Opens a trip and returns its ID; fails the test if that does not work. */
    public String open(String accessToken, String json) {
        HttpResponse<String> response = create(accessToken, json);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.trip.id");
    }

    public HttpResponse<String> browse(String accessToken, String query) {
        return http.get(TRIPS + (query.isEmpty() ? "" : "?" + query), bearer(accessToken));
    }

    /** A browse around the place, 5 km wide. */
    public HttpResponse<String> browseNear(String accessToken, Place place, String moreQuery) {
        return browse(accessToken, "latitude=%s&longitude=%s&radiusKm=5%s".formatted(place.latitude(),
                place.longitude(), moreQuery.isEmpty() ? "" : "&" + moreQuery));
    }

    public HttpResponse<String> hosted(String accessToken) {
        return http.get(HOSTED, bearer(accessToken));
    }

    public HttpResponse<String> detail(String accessToken, String tripId) {
        return http.get(TRIPS + "/" + tripId, bearer(accessToken));
    }

    public HttpResponse<String> requestToJoin(String accessToken, String tripId) {
        return http.post(TRIPS + "/" + tripId + "/join-requests", bearer(accessToken));
    }

    /** Sends a join request and returns its ID; fails the test if that does not work. */
    public String join(String accessToken, String tripId) {
        HttpResponse<String> response = requestToJoin(accessToken, tripId);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.id");
    }

    public HttpResponse<String> incoming(String accessToken, String query) {
        return http.get(INCOMING + (query.isEmpty() ? "" : "?" + query), bearer(accessToken));
    }

    public HttpResponse<String> outgoing(String accessToken) {
        return http.get(OUTGOING, bearer(accessToken));
    }

    public HttpResponse<String> accept(String accessToken, String requestId) {
        return http.post("/api/v1/join-requests/" + requestId + "/accept", bearer(accessToken));
    }

    public HttpResponse<String> reject(String accessToken, String requestId) {
        return http.post("/api/v1/join-requests/" + requestId + "/reject", bearer(accessToken));
    }

    private static String[] bearer(String accessToken) {
        return new String[] {"Authorization", "Bearer " + accessToken};
    }

    public record Place(double latitude, double longitude) {
    }
}
