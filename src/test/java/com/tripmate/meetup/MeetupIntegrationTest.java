package com.tripmate.meetup;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.chat.ChatApi;
import com.tripmate.identity.VerificationApi;
import com.tripmate.meetup.MeetupApi.Ladder;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.TestImages;
import com.tripmate.trips.Traveller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class MeetupIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theFullLadderEndsWithTheTripLockedAndContactDetailsUnlocked() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed()
                .confirmed().startingIn(10).bothCheckedIn();

        assertThat(stage(ladder.hostApi().view(ladder.host().token(), ladder.matchId()))).isEqualTo("FEEDBACK");
        assertThat(ladder.hostApi().rate(ladder.host().token(), ladder.matchId(), 4, null).statusCode())
                .isEqualTo(200);
        assertThat(stage(ladder.hostApi().view(ladder.host().token(), ladder.matchId())))
                .isEqualTo("WAITING_FOR_OTHER");
        HttpResponse<String> last = ladder.companionApi().rate(ladder.companion().token(), ladder.matchId(), 5,
                "Felt easy");

        assertThat(stage(last)).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("select status from trip_members where id = ?::uuid", String.class,
                ladder.matchId())).isEqualTo("LOCKED");
        HttpResponse<String> contact = new ChatApi(ladder.companion().http()).contact(ladder.companion().token(),
                ladder.conversationId());
        assertThat(contact.statusCode()).as(contact.body()).isEqualTo(200);
        String chat = new ChatApi(ladder.companion().http()).conversation(ladder.companion().token(),
                ladder.conversationId()).body();
        assertThat(JsonPath.<List<String>>read(chat, "$.messages[*].systemCode")).contains("CONTACT_UNLOCKED");
    }

    @Test
    void nobodyOutsideTheMatchCanReachAnyOfItsRoutes() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed();
        Traveller stranger = Traveller.signUp(port, mailpit);
        MeetupApi api = new MeetupApi(stranger.http());
        String token = stranger.token();
        String match = ladder.matchId();

        for (HttpResponse<String> response : List.of(api.view(token, match), api.venues(token, match, ""),
                api.videoCall(token, match), api.propose(token, match, ladder.venueId(), ladder.firstAllowedStart()),
                api.confirm(token, match), api.cancel(token, match, "SAFETY"),
                api.checkIn(token, match, ladder.venueLatitude(), ladder.venueLongitude(), 5.0, TestImages.jpeg(0)),
                api.safe(token, match), api.rate(token, match, 1, null),
                api.view(token, UUID.randomUUID().toString()))) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
            assertThat(response.body()).contains("\"not_found\"");
        }
        assertThat(stage(ladder.hostApi().view(ladder.host().token(), match))).isEqualTo("AWAITING_CONFIRMATION");
    }

    @Test
    void aPendingRequestIsNotAMatch() {
        Traveller host = Traveller.signUp(port, mailpit);
        Traveller requester = Traveller.signUp(port, mailpit);
        String tripId = host.trips().open(host.token(),
                com.tripmate.trips.TripApi.tripJson(com.tripmate.trips.TripApi.uniquePlace(), 2));
        String requestId = requester.trips().join(requester.token(), tripId);

        assertThat(new MeetupApi(host.http()).view(host.token(), requestId).statusCode()).isEqualTo(404);
        assertThat(new MeetupApi(requester.http()).view(requester.token(), requestId).statusCode()).isEqualTo(404);
    }

    @Test
    void theVideoCallNeedsBothPeopleBeforeAMeetupCanBeProposed() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).withEmergencyContacts();
        String start = ladder.firstAllowedStart();

        ladder.hostApi().videoCall(ladder.host().token(), ladder.matchId());
        HttpResponse<String> halfway = ladder.hostApi().view(ladder.host().token(), ladder.matchId());
        assertThat(JsonPath.<Boolean>read(halfway.body(), "$.videoCall.confirmedByMe")).isTrue();
        assertThat(JsonPath.<Boolean>read(halfway.body(), "$.videoCall.verified")).isFalse();
        HttpResponse<String> refused = ladder.hostApi().propose(ladder.host().token(), ladder.matchId(),
                ladder.venueId(), start);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("\"video_call_needed\"");

        ladder.companionApi().videoCall(ladder.companion().token(), ladder.matchId());
        assertThat(jdbc.queryForObject("select video_verified_at is not null from trip_members where id = ?::uuid",
                Boolean.class, ladder.matchId())).isTrue();
        assertThat(stage(ladder.hostApi().view(ladder.host().token(), ladder.matchId()))).isEqualTo("ARRANGE_MEETUP");
    }

    @Test
    void proposingNeedsAnEmergencyContactAnAllowlistedVenueAndADaylightTimeBeforeTheTrip() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified();
        MeetupApi api = ladder.companionApi();
        String token = ladder.companion().token();
        String start = ladder.firstAllowedStart();

        assertProblem(api.propose(token, ladder.matchId(), ladder.venueId(), start), 409, "emergency_contact_needed");
        api.setEmergencyContact(token, "+91 90000 00009");

        assertProblem(api.propose(token, ladder.matchId(), UUID.randomUUID().toString(), start), 400,
                "venue_unavailable");
        String inactive = jdbc.queryForObject("""
                insert into verified_venues (name, address, city, category, latitude, longitude, active)
                values ('Closed cafe', 'Nowhere', 'Testpur', 'CAFE', ?, ?, false) returning id::text""",
                String.class, ladder.venueLatitude(), ladder.venueLongitude());
        assertProblem(api.propose(token, ladder.matchId(), inactive, start), 400, "venue_unavailable");

        Instant middayish = ladder.firstWindowMiddle();
        assertProblem(api.propose(token, ladder.matchId(), ladder.venueId(), middayish.plus(12, ChronoUnit.HOURS)
                .toString()), 400, "meetup_not_in_daylight");
        assertProblem(api.propose(token, ladder.matchId(), ladder.venueId(), middayish.plus(9, ChronoUnit.DAYS)
                .toString()), 400, "meetup_time_out_of_range");
        HttpResponse<String> proposed = api.propose(token, ladder.matchId(), ladder.venueId(), start);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(proposed.body(), "$.meetup.proposedByMe")).isTrue();
        assertProblem(api.propose(token, ladder.matchId(), ladder.venueId(), start), 409, "meetup_exists");
        assertProblem(api.confirm(token, ladder.matchId()), 409, "own_proposal");
    }

    @Test
    void venuesComeOnlyFromTheAllowlistNearThePointWithDaylightWindows() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc);

        HttpResponse<String> near = ladder.hostApi().venues(ladder.host().token(), ladder.matchId(), "");
        assertThat(JsonPath.<List<String>>read(near.body(), "$.venues[*].venue.id")).contains(ladder.venueId());
        assertThat(JsonPath.<List<?>>read(near.body(), "$.venues[0].windows")).isNotEmpty();
        HttpResponse<String> goa = ladder.hostApi().venues(ladder.host().token(), ladder.matchId(),
                "latitude=15.4989&longitude=73.8278&radiusKm=5");
        assertThat(JsonPath.<List<String>>read(goa.body(), "$.venues[*].venue.city")).containsOnly("Goa");
        assertProblem(ladder.hostApi().venues(ladder.host().token(), ladder.matchId(), "radiusKm=500"), 400,
                "invalid_location");
        assertProblem(ladder.hostApi().venues(ladder.host().token(), ladder.matchId(), "latitude=12"), 400,
                "invalid_location");
    }

    @Test
    void checkInNeedsTheGeofenceAndASelfieAndStoresNothingWhenRefused() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed()
                .confirmed();
        MeetupApi api = ladder.hostApi();
        String token = ladder.host().token();

        assertProblem(api.checkIn(token, ladder.matchId(), ladder.venueLatitude(), ladder.venueLongitude(), 10.0,
                TestImages.jpeg(0)), 409, "check_in_closed");
        ladder.startingIn(10);
        assertProblem(api.checkIn(token, ladder.matchId(), ladder.venueLatitude() + 0.002, ladder.venueLongitude(),
                10.0, TestImages.jpeg(0)), 409, "not_at_venue");
        assertProblem(api.checkIn(token, ladder.matchId(), ladder.venueLatitude(), ladder.venueLongitude(), 900.0,
                TestImages.jpeg(0)), 409, "location_too_rough");
        assertProblem(api.checkIn(token, ladder.matchId(), ladder.venueLatitude(), ladder.venueLongitude(), 10.0,
                TestImages.gif()), 400, "invalid_image");
        assertThat(jdbc.queryForObject("select count(*) from meetup_checkins where meetup_id = ?::uuid "
                + "and (arrived_at is not null or selfie_key is not null)", Integer.class, ladder.meetupId())).isZero();

        HttpResponse<String> arrived = api.checkIn(token, ladder.matchId(), ladder.venueLatitude() + 0.0005,
                ladder.venueLongitude(), 10.0, TestImages.jpegWithExif());
        assertThat(arrived.statusCode()).as(arrived.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(arrived.body(), "$.meetupCompleted")).isFalse();
        assertProblem(api.checkIn(token, ladder.matchId(), ladder.venueLatitude(), ladder.venueLongitude(), 10.0,
                TestImages.jpeg(0)), 409, "already_checked_in");
        Map<String, Object> row = jdbc.queryForMap("select selfie_key, selfie_purge_due_at, distance_meters "
                + "from meetup_checkins where meetup_id = ?::uuid and arrived_at is not null", ladder.meetupId());
        assertThat((String) row.get("selfie_key")).startsWith("meetup-selfies/");
        assertThat(row.get("selfie_purge_due_at")).isNotNull();

        String companionView = ladder.companionApi().view(ladder.companion().token(), ladder.matchId()).body();
        assertThat(JsonPath.<Boolean>read(companionView, "$.meetup.theyArrived")).isTrue();
        assertThat(companionView).doesNotContain(String.valueOf(ladder.venueLatitude() + 0.0005), "distance",
                ladder.host().userId());
    }

    @Test
    void aLowComfortRatingQuietlyStopsTheMatchForBoth() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed()
                .confirmed().startingIn(10).bothCheckedIn();

        HttpResponse<String> mine = ladder.companionApi().rate(ladder.companion().token(), ladder.matchId(), 2, null);
        HttpResponse<String> theirs = ladder.hostApi().view(ladder.host().token(), ladder.matchId());

        assertThat(stage(mine)).isEqualTo("DID_NOT_PROCEED");
        assertThat(stage(theirs)).isEqualTo("DID_NOT_PROCEED");
        assertThat(JsonPath.<Object>read(theirs.body(), "$.meetup")).isNull();
        assertThat(JsonPath.<Object>read(theirs.body(), "$.videoCall")).isNull();
        assertThat(theirs.body()).doesNotContainIgnoringCase("rating").doesNotContainIgnoringCase("comfort")
                .doesNotContain(ladder.companion().userId());
        assertProblem(ladder.hostApi().rate(ladder.host().token(), ladder.matchId(), 5, null), 409, "match_closed");
        Map<String, Object> member = jdbc.queryForMap("select status, cancellation_type from trip_members "
                + "where id = ?::uuid", ladder.matchId());
        assertThat(member).containsEntry("status", "CANCELLED").containsEntry("cancellation_type", "SAFETY");
        assertThat(jdbc.queryForObject("select filled_slots from trips where id = ?::uuid", Integer.class,
                ladder.tripId())).isZero();
        HttpResponse<String> send = new ChatApi(ladder.host().http()).send(ladder.host().token(),
                ladder.conversationId(), UUID.randomUUID(), "still there?");
        assertThat(send.body()).contains("\"conversation_closed\"");
        assertNoTrustEventFor(ladder.companion().userId());
    }

    @Test
    void bothRatingAtTheSameTimeLocksTheTripExactlyOnce() throws Exception {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed()
                .confirmed().startingIn(10).bothCheckedIn();

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var host = pool.submit(() -> {
                start.await();
                return ladder.hostApi().rate(ladder.host().token(), ladder.matchId(), 4, null);
            });
            var companion = pool.submit(() -> {
                start.await();
                return ladder.companionApi().rate(ladder.companion().token(), ladder.matchId(), 4, null);
            });
            start.countDown();
            assertThat(host.get().statusCode()).as(host.get().body()).isEqualTo(200);
            assertThat(companion.get().statusCode()).as(companion.get().body()).isEqualTo(200);
        } finally {
            pool.shutdown();
        }

        assertThat(jdbc.queryForObject("select status from trip_members where id = ?::uuid", String.class,
                ladder.matchId())).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where conversation_id = ?::uuid "
                + "and system_code = 'CONTACT_UNLOCKED'", Integer.class, ladder.conversationId())).isEqualTo(1);
    }

    @Test
    void aSafetyCancellationEndsTheMatchSilentlyAndCostsNothing() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed()
                .confirmed().startingIn(60);

        HttpResponse<String> cancelled = ladder.companionApi().cancel(ladder.companion().token(), ladder.matchId(),
                "SAFETY");

        assertThat(stage(cancelled)).isEqualTo("DID_NOT_PROCEED");
        String hostView = ladder.hostApi().view(ladder.host().token(), ladder.matchId()).body();
        assertThat(JsonPath.<String>read(hostView, "$.stage")).isEqualTo("DID_NOT_PROCEED");
        assertThat(hostView).doesNotContain("SAFETY").doesNotContain("cancel");
        assertNoTrustEventFor(ladder.companion().userId());
    }

    @Test
    void aSafetyCancellationRaisesAModerationFlagWithOnlyWhoWhichMeetupAndWhen() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed()
                .confirmed().startingIn(60);
        String meetupId = jdbc.queryForObject("select id::text from meetups where member_id = ?::uuid",
                String.class, ladder.matchId());
        Instant before = Instant.now().minusSeconds(5);

        stage(ladder.companionApi().cancel(ladder.companion().token(), ladder.matchId(), "SAFETY"));

        List<Map<String, Object>> flags = jdbc.queryForList("select aggregate_type, aggregate_id::text as aggregate, "
                + "payload::text as payload from outbox_events where event_type = 'SAFETY_MEETUP_CANCELLED' "
                + "and aggregate_id = ?::uuid", meetupId);
        assertThat(flags).hasSize(1);
        assertThat(flags.getFirst()).containsEntry("aggregate_type", "SAFETY_REPORT");
        String payload = (String) flags.getFirst().get("payload");
        assertThat(JsonPath.<Map<String, Object>>read(payload, "$")).containsOnlyKeys("userId", "meetupId",
                "tripId", "cancelledAt");
        assertThat(JsonPath.<String>read(payload, "$.userId")).isEqualTo(ladder.companion().userId());
        assertThat(JsonPath.<String>read(payload, "$.meetupId")).isEqualTo(meetupId);
        assertThat(JsonPath.<String>read(payload, "$.tripId")).isEqualTo(ladder.tripId());
        assertThat(Instant.parse(JsonPath.read(payload, "$.cancelledAt"))).isAfter(before);
        assertThat(payload).doesNotContainIgnoringCase("rating").doesNotContainIgnoringCase("comfort")
                .doesNotContainIgnoringCase("note");
    }

    @Test
    void aLowComfortRatingRaisesNoFlagThatCouldRevealIt() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed()
                .confirmed().startingIn(10).bothCheckedIn();
        ladder.hostApi().rate(ladder.host().token(), ladder.matchId(), 4, null);

        stage(ladder.companionApi().rate(ladder.companion().token(), ladder.matchId(), 1, "Made me uneasy"));

        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = "
                + "'SAFETY_MEETUP_CANCELLED' and payload ->> 'tripId' = ?", Integer.class, ladder.tripId())).isZero();
        assertThat(jdbc.queryForList("select payload::text from outbox_events where payload ->> 'tripId' = ?",
                String.class, ladder.tripId())).noneMatch(payload -> payload.toLowerCase().contains("rating")
                || payload.contains("uneasy"));
    }

    @Test
    void aLateChangeOfPlanIsATrustInputAndAllowsANewProposal() {
        Ladder ladder = Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed()
                .confirmed().startingIn(120);

        HttpResponse<String> cancelled = ladder.hostApi().cancel(ladder.host().token(), ladder.matchId(),
                "CONVENIENCE");

        assertThat(stage(cancelled)).isEqualTo("ARRANGE_MEETUP");
        assertThat(JsonPath.<String>read(cancelled.body(), "$.meetup.cancellation")).isEqualTo("CONVENIENCE");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'MEETUP_LATE_CANCEL' "
                + "and aggregate_id = ?::uuid", Integer.class, ladder.host().userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = "
                + "'SAFETY_MEETUP_CANCELLED' and payload ->> 'tripId' = ?", Integer.class, ladder.tripId()))
                .as("a change of plan is not a safety flag").isZero();
        HttpResponse<String> again = ladder.hostApi().propose(ladder.host().token(), ladder.matchId(),
                ladder.venueId(), ladder.firstAllowedStart());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
    }

    @Test
    void emergencyContactsAreTheCallersOwnMaskedAndNeverTheirOwnNumber() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        MeetupApi api = new MeetupApi(traveller.http());
        String own = new VerificationApi(traveller.http(), mailpit).verifyPhone(traveller.email(), traveller.token());

        assertThat(JsonPath.<Object>read(api.emergencyContact(traveller.token()).body(), "$.contact")).isNull();
        assertProblem(api.setEmergencyContact(traveller.token(), "12345"), 400, "invalid_phone_number");
        assertProblem(api.setEmergencyContact(traveller.token(), own), 400, "emergency_contact_is_you");
        HttpResponse<String> set = api.setEmergencyContact(traveller.token(), "98765 43210");
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(set.body(), "$.contact.maskedPhone")).endsWith("210").doesNotContain("98765");
        byte[] stored = jdbc.queryForObject("select phone_ciphertext from emergency_contacts where user_id = ?::uuid",
                byte[].class, traveller.userId());
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("9876543210");
        assertThat(api.removeEmergencyContact(traveller.token()).statusCode()).isEqualTo(204);
        assertThat(JsonPath.<Object>read(api.emergencyContact(traveller.token()).body(), "$.contact")).isNull();
    }

    private void assertNoTrustEventFor(String userId) {
        assertThat(jdbc.queryForList("select event_type from outbox_events where aggregate_id = ?::uuid",
                String.class, userId)).noneMatch(type -> type.startsWith("MEETUP"));
        assertThat(jdbc.queryForList("select payload::text from outbox_events", String.class))
                .noneMatch(payload -> payload.toLowerCase().contains("comfort"));
    }

    private static String stage(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return JsonPath.read(response.body(), "$.stage");
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"" + code + "\"");
    }
}
