package com.tripmate.safety;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.chat.ChatApi;
import com.tripmate.identity.emergency.MailboxEmergencyAlertSender;
import com.tripmate.meetup.MeetupApi;
import com.tripmate.safety.service.LiveTripSafetyJob;
import com.tripmate.settlement.SettlementApi.Crew;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
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
class LiveTripIntegrationTest {

    private static final String PING = "TripMates: are you safe?";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LiveTripSafetyJob job;

    @Test
    void onlyTravellersStillOnTheTripReachAnyLiveRoute() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        String sosId = raiseSos(crew, crew.host(), null);
        Traveller stranger = Traveller.signUp(port, mailpit);
        ChatApi.Pair notLocked = ChatApi.Pair.accepted(port, mailpit);

        for (Traveller outsider : List.of(stranger, notLocked.companion(), notLocked.host())) {
            SafetyApi api = new SafetyApi(outsider.http());
            String token = outsider.token();
            String trip = crew.tripId();
            for (HttpResponse<String> response : List.of(api.live(token, trip), api.share(token, trip, 12, null),
                    api.stopSharing(token, trip), api.checkIn(token, trip, null, null),
                    api.sos(token, trip, UUID.randomUUID(), null, null), api.resolve(token, sosId),
                    api.premortem(token, trip), api.live(token, UUID.randomUUID().toString()))) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
                assertThat(response.body()).contains("\"not_found\"");
            }
        }
        HttpResponse<String> early = new SafetyApi(notLocked.companion().http()).live(notLocked.companion().token(),
                notLocked.tripId());
        assertThat(early.statusCode()).as("accepted but not met in person yet").isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from sos_events where trip_id = ?::uuid", Long.class,
                crew.tripId())).isEqualTo(1);
    }

    @Test
    void sharingNeedsALockedTripAndAnEmergencyContact() {
        ChatApi.Pair notLocked = ChatApi.Pair.accepted(port, mailpit);
        SafetyApi hostApi = new SafetyApi(notLocked.host().http());
        assertProblem(hostApi.share(notLocked.host().token(), notLocked.tripId(), 12, null), 409, "trip_not_locked");

        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        SafetyApi api = new SafetyApi(crew.host().http());
        String token = crew.host().token();
        assertProblem(api.share(token, crew.tripId(), 12, null), 409, "emergency_contact_needed");
        addEmergencyContact(crew.host(), "+91 90000 00031");
        assertProblem(api.share(token, crew.tripId(), 5, null), 400, "invalid_check_in_interval");
        assertProblem(api.share(token, crew.tripId(), 12, "x".repeat(301)), 400, "invalid_note");
        assertProblem(api.checkIn(token, crew.tripId(), null, null), 409, "not_sharing");

        HttpResponse<String> shared = api.share(token, crew.tripId(), 12, "Staying at Om Beach guesthouse");
        assertThat(shared.statusCode()).as(shared.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(shared.body(), "$.sharing.checkInEveryHours")).isEqualTo(12);
        assertThat(JsonPath.<Boolean>read(shared.body(), "$.sharing.noteSet")).isTrue();
        assertThat(JsonPath.<String>read(shared.body(), "$.phase")).isEqualTo("BEFORE");
        Instant due = Instant.parse(JsonPath.read(shared.body(), "$.nextCheckIn.dueAt"));
        Instant tripStart = jdbc.queryForObject("select (start_date::timestamp at time zone 'Asia/Kolkata') "
                + "from trips where id = ?::uuid", java.sql.Timestamp.class, crew.tripId()).toInstant();
        assertThat(due).as("first check-in one interval into the trip").isEqualTo(tripStart.plus(12, ChronoUnit.HOURS));
        byte[] note = jdbc.queryForObject("select note_ciphertext from itinerary_shares where trip_id = ?::uuid",
                byte[].class, crew.tripId());
        assertThat(new String(note, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Om Beach");

        HttpResponse<String> stopped = api.stopSharing(token, crew.tripId());
        assertThat(JsonPath.<Object>read(stopped.body(), "$.sharing")).isNull();
        assertThat(JsonPath.<Object>read(stopped.body(), "$.nextCheckIn")).isNull();
        assertThat(jdbc.queryForList("select status from checkins where trip_id = ?::uuid", String.class,
                crew.tripId())).containsExactly("CANCELLED");
    }

    @Test
    void aCheckInKeepsOnlyARoundedPointInTheClearAndSchedulesTheNext() {
        Crew crew = liveToday();
        Traveller host = crew.host();
        SafetyApi api = new SafetyApi(host.http());
        addEmergencyContact(host, "+91 90000 00034");
        assertThat(api.share(host.token(), crew.tripId(), 6, null).statusCode()).isEqualTo(200);
        Map<String, Object> trip = jdbc.queryForMap("select destination_latitude, destination_longitude from trips "
                + "where id = ?::uuid", crew.tripId());
        double latitude = ((Number) trip.get("destination_latitude")).doubleValue() + 0.0731;
        double longitude = ((Number) trip.get("destination_longitude")).doubleValue();

        HttpResponse<String> checked = api.checkIn(host.token(), crew.tripId(), latitude, longitude);

        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        assertThat(JsonPath.<Double>read(checked.body(), "$.lastCheckIn.kmFromDestination")).isBetween(7.0, 9.0);
        Map<String, Object> row = jdbc.queryForMap("select rounded_latitude, location_ciphertext from checkins "
                + "where trip_id = ?::uuid and status = 'CONFIRMED'", crew.tripId());
        double stored = ((Number) row.get("rounded_latitude")).doubleValue();
        assertThat(stored).isEqualTo(Math.round(latitude * 100) / 100.0);
        assertThat(new String((byte[]) row.get("location_ciphertext"), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(String.valueOf(latitude).substring(0, 5));
        assertThat(jdbc.queryForList("select status from checkins where trip_id = ?::uuid order by created_at",
                String.class, crew.tripId())).containsExactly("CONFIRMED", "PENDING");
        String buddyView = new SafetyApi(crew.companion(0).http()).live(crew.companion(0).token(), crew.tripId()).body();
        assertThat(JsonPath.<String>read(buddyView, "$.buddies[0].lastCheckInAt")).isNotNull();
        assertThat(JsonPath.<Map<String, Object>>read(buddyView, "$.buddies[0]"))
                .doesNotContainKeys("latitude", "longitude", "roundedLatitude", "location");
    }

    @Test
    void sosAlertsTheContactWithTheExactPlaceAndFlagsItForModerationButNeverTheTrustScore() {
        Crew crew = liveToday();
        Traveller host = crew.host();
        addEmergencyContact(host, "+91 90000 00032");
        new SafetyApi(host.http()).share(host.token(), crew.tripId(), 12, "Room 4 at Sea Breeze");
        UUID clientId = UUID.randomUUID();
        Instant before = Instant.now();

        HttpResponse<String> raised = new SafetyApi(host.http()).sos(host.token(), crew.tripId(), clientId,
                14.551234, 74.312345);

        assertThat(raised.statusCode()).as(raised.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(raised.body(), "$.openSos.contactAlerted")).isTrue();
        mailpit.awaitSubject(host.email(), MailboxEmergencyAlertSender.SUBJECT);
        String alert = mailpit.latestText(host.email(), MailboxEmergencyAlertSender.SUBJECT);
        assertThat(alert).contains("pressed SOS").contains("https://maps.google.com/?q=14.551234,74.312345")
                .contains("Room 4 at Sea Breeze").contains(firstName(crew.companion(0)));
        String sosId = JsonPath.read(raised.body(), "$.openSos.id");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'SAFETY_SOS_RAISED' "
                + "and aggregate_id = ?::uuid", Long.class, sosId)).isEqualTo(1);
        assertThat(trustInputsSince(host, before)).as("nothing about the person goes to the trust score").isZero();

        HttpResponse<String> again = new SafetyApi(host.http()).sos(host.token(), crew.tripId(), clientId, null, null);
        assertThat(JsonPath.<String>read(again.body(), "$.openSos.id")).isEqualTo(sosId);
        assertThat(mailpit.countWithSubject(host.email(), MailboxEmergencyAlertSender.SUBJECT)).isEqualTo(1);

        assertProblem(new SafetyApi(crew.companion(0).http()).resolve(crew.companion(0).token(), sosId), 404,
                "not_found");
        HttpResponse<String> resolved = new SafetyApi(host.http()).resolve(host.token(), sosId);
        assertThat(JsonPath.<Object>read(resolved.body(), "$.openSos")).isNull();
    }

    @Test
    void sosIsRecordedAndFlaggedEvenWithNoEmergencyContactToAlert() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);

        HttpResponse<String> raised = new SafetyApi(crew.companion(0).http()).sos(crew.companion(0).token(),
                crew.tripId(), UUID.randomUUID(), null, null);

        assertThat(raised.statusCode()).as(raised.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(raised.body(), "$.openSos.contactAlerted")).isFalse();
        assertThat(jdbc.queryForMap("select status, alert_attempts from sos_events where user_id = ?::uuid",
                crew.companion(0).userId())).containsEntry("status", "OPEN").containsEntry("alert_attempts", 1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'SAFETY_SOS_RAISED' "
                + "and payload ->> 'userId' = ?", Long.class, crew.companion(0).userId())).isEqualTo(1);
    }

    @Test
    void anUnansweredCheckInIsChasedThenEscalatedAndTheNetStaysUp() {
        Crew crew = liveToday();
        Traveller host = crew.host();
        addEmergencyContact(host, "+91 90000 00033");
        new SafetyApi(host.http()).share(host.token(), crew.tripId(), 3, null);
        Instant before = Instant.now();
        jdbc.update("update checkins set scheduled_at = now() - interval '5 minutes' where trip_id = ?::uuid "
                + "and status = 'PENDING'", crew.tripId());

        job.sweep();
        mailpit.awaitSubject(host.email(), PING);
        assertThat(mailpit.countWithSubject(host.email(), MailboxEmergencyAlertSender.SUBJECT)).isZero();

        jdbc.update("update checkins set scheduled_at = now() - interval '61 minutes' where trip_id = ?::uuid "
                + "and status = 'PENDING'", crew.tripId());
        job.sweep();
        job.sweep();

        mailpit.awaitSubject(host.email(), MailboxEmergencyAlertSender.SUBJECT);
        assertThat(mailpit.countWithSubject(host.email(), MailboxEmergencyAlertSender.SUBJECT)).isEqualTo(1);
        assertThat(mailpit.latestText(host.email(), MailboxEmergencyAlertSender.SUBJECT))
                .contains("has not answered a safety check-in").contains("not checked in on this trip yet");
        assertThat(jdbc.queryForList("select status from checkins where trip_id = ?::uuid order by created_at",
                String.class, crew.tripId())).containsExactly("MISSED", "PENDING");
        assertThat(trustInputsSince(host, before)).as("missing a check-in costs nothing").isZero();
    }

    @Test
    void thePremortemListsOnlyTheCallersOwnBigGapsInFixedWords() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        setVibe(crew.host(), "SHOESTRING", "PACKED", "EARLY_BIRD", "DETAILED_ITINERARY", 3, "STRICT_SPLIT");
        SafetyApi companionApi = new SafetyApi(crew.companion(0).http());

        HttpResponse<String> before = companionApi.premortem(crew.companion(0).token(), crew.tripId());
        assertThat(before.statusCode()).as(before.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(before.body(), "$.myVibeSet")).isFalse();
        assertThat(JsonPath.<List<Object>>read(before.body(), "$.pairs[0].topics")).isEmpty();

        setVibe(crew.companion(0), "COMFORT", "SLOW", "NIGHT_OWL", "DETAILED_ITINERARY", 3, "STRICT_SPLIT");
        HttpResponse<String> after = companionApi.premortem(crew.companion(0).token(), crew.tripId());

        assertThat(JsonPath.<List<String>>read(after.body(), "$.pairs[0].topics[*].dimension"))
                .containsExactly("BUDGET", "PACE", "WAKE_TIME");
        assertThat(JsonPath.<String>read(after.body(), "$.pairs[0].topics[0].gap"))
                .isEqualTo("You plan for comfort; Asha plans for a shoestring budget.");
        assertThat(after.body()).doesNotContain("socialEnergy", "riskAppetite", crew.host().userId());
    }

    private void setVibe(Traveller traveller, String budget, String pace, String wake, String planning, int scales,
                         String expenses) {
        com.tripmate.vibe.VibeApi vibe = new com.tripmate.vibe.VibeApi(traveller.http());
        assertThat(vibe.submit(traveller.token(), com.tripmate.vibe.VibeApi.FIRST_OPTIONS).statusCode()).isEqualTo(200);
        HttpResponse<String> adjusted = vibe.adjust(traveller.token(), """
                {"budgetBand":"%s","pace":"%s","wakeTime":"%s","planningStyle":"%s","socialEnergy":%d,
                 "riskAppetite":%d,"foodAdventurousness":%d,"expenseSplitStyle":"%s"}"""
                .formatted(budget, pace, wake, planning, scales, scales, scales, expenses));
        assertThat(adjusted.statusCode()).as(adjusted.body()).isEqualTo(200);
    }

    /** A locked crew whose trip is on today. */
    private Crew liveToday() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        jdbc.update("update trips set start_date = current_date - 1, end_date = current_date + 2 where id = ?::uuid",
                crew.tripId());
        return crew;
    }

    private String raiseSos(Crew crew, Traveller who, Double latitude) {
        HttpResponse<String> raised = new SafetyApi(who.http()).sos(who.token(), crew.tripId(), UUID.randomUUID(),
                latitude, latitude);
        assertThat(raised.statusCode()).as(raised.body()).isEqualTo(201);
        return JsonPath.read(raised.body(), "$.openSos.id");
    }

    /** Outbox events about the user, as the trust score would read them, since the instant. */
    private long trustInputsSince(Traveller traveller, Instant since) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_type = 'USER' "
                + "and aggregate_id = ?::uuid and created_at >= ?", Long.class, traveller.userId(),
                java.sql.Timestamp.from(since));
    }

    private static void addEmergencyContact(Traveller traveller, String phone) {
        HttpResponse<String> set = new MeetupApi(traveller.http()).setEmergencyContact(traveller.token(), phone);
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
    }

    private String firstName(Traveller traveller) {
        return jdbc.queryForObject("select first_name from user_public_facts where user_id = ?::uuid", String.class,
                traveller.userId());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"" + code + "\"");
    }
}
