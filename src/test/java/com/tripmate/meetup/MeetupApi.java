package com.tripmate.meetup;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.chat.ChatApi;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.LocalHttp.Part;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.TestImages;
import com.tripmate.trips.Traveller;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls to the match and emergency contact routes, and a match walked up the ladder to any rung. */
public final class MeetupApi {

    public static final String EMERGENCY_CONTACT = "/api/v1/users/me/emergency-contact";

    private final LocalHttp http;

    public MeetupApi(LocalHttp http) {
        this.http = http;
    }

    static String base(String matchId) {
        return "/api/v1/matches/" + matchId;
    }

    public HttpResponse<String> view(String token, String matchId) {
        return http.get(base(matchId), bearer(token));
    }

    public HttpResponse<String> venues(String token, String matchId, String query) {
        return http.get(base(matchId) + "/venues" + (query.isEmpty() ? "" : "?" + query), bearer(token));
    }

    public HttpResponse<String> videoCall(String token, String matchId) {
        return http.post(base(matchId) + "/video-call", bearer(token));
    }

    public static String proposalJson(String venueId, String scheduledAt, boolean plusOne) {
        return """
                {"venueId":"%s","scheduledAt":"%s","bringingPlusOne":%s}""".formatted(venueId, scheduledAt, plusOne);
    }

    public HttpResponse<String> propose(String token, String matchId, String venueId, String scheduledAt) {
        return http.postJson(base(matchId) + "/meetup", proposalJson(venueId, scheduledAt, false), bearer(token));
    }

    public HttpResponse<String> confirm(String token, String matchId) {
        return http.postJson(base(matchId) + "/meetup/confirm", "{\"bringingPlusOne\":true}", bearer(token));
    }

    public HttpResponse<String> cancel(String token, String matchId, String type) {
        return http.postJson(base(matchId) + "/meetup/cancel", "{\"type\":\"" + type + "\"}", bearer(token));
    }

    public static List<Part> checkInParts(double latitude, double longitude, Double accuracy, byte[] selfie) {
        List<Part> parts = new ArrayList<>(List.of(Part.text("latitude", String.valueOf(latitude)),
                Part.text("longitude", String.valueOf(longitude))));
        if (accuracy != null) {
            parts.add(Part.text("accuracyMeters", String.valueOf(accuracy)));
        }
        parts.add(Part.jpeg("selfie", selfie));
        return parts;
    }

    public HttpResponse<String> checkIn(String token, String matchId, double latitude, double longitude,
                                        Double accuracy, byte[] selfie) {
        return http.multipart("POST", base(matchId) + "/meetup/check-in",
                checkInParts(latitude, longitude, accuracy, selfie), bearer(token));
    }

    public HttpResponse<String> safe(String token, String matchId) {
        return http.post(base(matchId) + "/meetup/safe", bearer(token));
    }

    public HttpResponse<String> rate(String token, String matchId, int rating, String notes) {
        return http.postJson(base(matchId) + "/meetup/feedback", notes == null
                ? "{\"comfortRating\":%d}".formatted(rating)
                : "{\"comfortRating\":%d,\"notes\":\"%s\"}".formatted(rating, notes), bearer(token));
    }

    public static String emergencyContactJson(String phone) {
        return """
                {"name":"Meera Rao","relationship":"SIBLING","phoneNumber":"%s"}""".formatted(phone);
    }

    public HttpResponse<String> setEmergencyContact(String token, String phone) {
        return http.putJson(EMERGENCY_CONTACT, emergencyContactJson(phone), bearer(token));
    }

    public HttpResponse<String> emergencyContact(String token) {
        return http.get(EMERGENCY_CONTACT, bearer(token));
    }

    public HttpResponse<String> removeEmergencyContact(String token) {
        return http.delete(EMERGENCY_CONTACT, bearer(token));
    }

    private static String[] bearer(String token) {
        return new String[] {"Authorization", "Bearer " + token};
    }

    /**
     * A host, an accepted companion and the match between them, with an
     * allowlisted venue placed a few metres from the trip's destination.
     */
    public record Ladder(Traveller host, Traveller companion, String tripId, String conversationId, String matchId,
                         String venueId, double venueLatitude, double venueLongitude, JdbcTemplate jdbc) {

        public static Ladder start(int port, MailpitClient mailpit, JdbcTemplate jdbc) {
            ChatApi.Pair pair = ChatApi.Pair.accepted(port, mailpit);
            String matchId = JsonPath.read(new ChatApi(pair.host().http()).inbox(pair.host().token()).body(),
                    "$.items[0].matchId");
            Map<String, Object> trip = jdbc.queryForMap(
                    "select destination_latitude, destination_longitude from trips where id = ?::uuid", pair.tripId());
            double latitude = ((Number) trip.get("destination_latitude")).doubleValue() + 0.0003;
            double longitude = ((Number) trip.get("destination_longitude")).doubleValue();
            String venueId = jdbc.queryForObject("""
                    insert into verified_venues (name, address, city, category, latitude, longitude, time_zone)
                    values ('Clock Tower Square', 'Market Road', 'Testpur', 'PUBLIC_LANDMARK', ?, ?, 'UTC')
                    returning id::text""", String.class, latitude, longitude);
            return new Ladder(pair.host(), pair.companion(), pair.tripId(), pair.conversationId(), matchId, venueId,
                    latitude, longitude, jdbc);
        }

        public MeetupApi hostApi() {
            return new MeetupApi(host.http());
        }

        public MeetupApi companionApi() {
            return new MeetupApi(companion.http());
        }

        public Ladder videoVerified() {
            assertOk(hostApi().videoCall(host.token(), matchId));
            assertOk(companionApi().videoCall(companion.token(), matchId));
            return this;
        }

        public Ladder withEmergencyContacts() {
            assertOk(hostApi().setEmergencyContact(host.token(), "+91 90000 00001"));
            assertOk(companionApi().setEmergencyContact(companion.token(), "+91 90000 00002"));
            return this;
        }

        /** The earliest start time the venue offers. */
        public String firstAllowedStart() {
            HttpResponse<String> venues = hostApi().venues(host.token(), matchId, "");
            assertOk(venues);
            List<String> starts = JsonPath.read(venues.body(),
                    "$.venues[?(@.venue.id == '" + venueId + "')].windows[0].earliest");
            assertThat(starts).as(venues.body()).hasSize(1);
            return starts.getFirst();
        }

        /**
         * Halfway through the venue's first daylight window: well inside
         * daylight, so the same clock time days later or twelve hours on is
         * clearly in or out of it whatever the latitude.
         */
        public java.time.Instant firstWindowMiddle() {
            HttpResponse<String> venues = hostApi().venues(host.token(), matchId, "");
            List<String> earliest = JsonPath.read(venues.body(),
                    "$.venues[?(@.venue.id == '" + venueId + "')].windows[0].earliest");
            List<String> latest = JsonPath.read(venues.body(),
                    "$.venues[?(@.venue.id == '" + venueId + "')].windows[0].latest");
            java.time.Instant from = java.time.Instant.parse(earliest.getFirst());
            java.time.Instant to = java.time.Instant.parse(latest.getFirst());
            return from.plus(java.time.Duration.between(from, to).dividedBy(2));
        }

        /** Proposed by the companion. */        public Ladder proposed() {
            HttpResponse<String> proposed = companionApi().propose(companion.token(), matchId, venueId,
                    firstAllowedStart());
            assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
            return this;
        }

        /** Confirmed by the host. */
        public Ladder confirmed() {
            assertOk(hostApi().confirm(host.token(), matchId));
            return this;
        }

        /** Moves the confirmed meet-up's start to {@code minutes} from now, so check-in is open (or long past). */
        public Ladder startingIn(int minutes) {
            jdbc.update("update meetups set scheduled_at = now() + make_interval(mins => ?) "
                    + "where member_id = ?::uuid and status = 'CONFIRMED'", minutes, matchId);
            return this;
        }

        public Ladder bothCheckedIn() {
            assertOk(hostApi().checkIn(host.token(), matchId, venueLatitude, venueLongitude, 12.0,
                    TestImages.jpeg(0)));
            assertOk(companionApi().checkIn(companion.token(), matchId, venueLatitude, venueLongitude, 12.0,
                    TestImages.jpeg(1)));
            return this;
        }

        public String meetupId() {
            return jdbc.queryForObject("select id::text from meetups where member_id = ?::uuid "
                    + "order by created_at desc limit 1", String.class, matchId);
        }

        private static void assertOk(HttpResponse<String> response) {
            assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        }
    }
}
