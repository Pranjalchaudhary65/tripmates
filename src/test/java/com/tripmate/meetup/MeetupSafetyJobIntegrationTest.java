package com.tripmate.meetup;

import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.meetup.MeetupApi.Ladder;
import com.tripmate.meetup.service.MeetupSafetyJob;
import com.tripmate.meetup.service.MeetupSelfiePurgeJob;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The 30/60-minute safety net and the selfie purge, run by hand against real mail and storage. */
@IntegrationTest
class MeetupSafetyJobIntegrationTest {

    private static final String PING = "TripMates: did you get to your meet-up?";

    private static final String ALERT = "TripMates: emergency contact alert (demo delivery)";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeetupSafetyJob safety;

    @Autowired
    private MeetupSelfiePurgeJob purge;

    @Autowired
    private ObjectStorage storage;

    @Test
    void thirtyMinutesInWhoeverHasNotCheckedInIsAskedOnce() {
        Ladder ladder = confirmedMeetup().startingIn(-35);

        safety.sweep();
        safety.sweep();

        mailpit.awaitSubject(ladder.host().email(), PING);
        mailpit.awaitSubject(ladder.companion().email(), PING);
        assertThat(mailpit.countWithSubject(ladder.host().email(), PING)).isEqualTo(1);
        assertThat(mailpit.countWithSubject(ladder.host().email(), ALERT)).isZero();
    }

    @Test
    void anHourInTheEmergencyContactIsAlertedForWhoeverIsNotAccountedFor() {
        Ladder ladder = confirmedMeetup().startingIn(-35);
        safety.sweep();
        ladder.companionApi().safe(ladder.companion().token(), ladder.matchId());
        ladder.startingIn(-65);

        safety.sweep();

        mailpit.awaitSubject(ladder.host().email(), ALERT);
        String alert = mailpit.latestText(ladder.host().email(), ALERT);
        assertThat(alert).contains("Meera Rao", "Clock Tower Square").doesNotContain("9000000001");
        assertThat(mailpit.countWithSubject(ladder.companion().email(), ALERT)).as("said they were safe").isZero();
        assertThat(jdbc.queryForObject("select emergency_contact_notified_at is not null from meetups where id = "
                + "?::uuid", Boolean.class, ladder.meetupId())).isTrue();
        assertThat(mailpit.subjectsTo(ladder.companion().email())).as("the other person is never told")
                .doesNotContain(ALERT);
    }

    @Test
    void someoneWhoCheckedInIsLeftAloneAndTheMeetupBecomesANoShowWhenCheckInCloses() {
        Ladder ladder = confirmedMeetup().startingIn(10);
        ladder.hostApi().checkIn(ladder.host().token(), ladder.matchId(), ladder.venueLatitude(),
                ladder.venueLongitude(), 10.0, TestImages.jpeg(0));
        ladder.startingIn(-95);

        safety.sweep();

        assertThat(jdbc.queryForObject("select status from meetups where id = ?::uuid", String.class,
                ladder.meetupId())).isEqualTo("NO_SHOW");
        List<String> noShows = jdbc.queryForList("select aggregate_id::text from outbox_events "
                + "where event_type = 'MEETUP_NO_SHOW' and payload->>'meetupId' = ?", String.class, ladder.meetupId());
        assertThat(noShows).containsExactly(ladder.companion().userId());
        assertThat(mailpit.countWithSubject(ladder.host().email(), PING)).isZero();
    }

    @Test
    void checkInSelfiesAreDeletedOnceTheirRetentionIsOver() {
        Ladder ladder = confirmedMeetup().startingIn(10);
        ladder.hostApi().checkIn(ladder.host().token(), ladder.matchId(), ladder.venueLatitude(),
                ladder.venueLongitude(), 10.0, TestImages.jpeg(0));
        String key = jdbc.queryForObject("select selfie_key from meetup_checkins where meetup_id = ?::uuid "
                + "and selfie_key is not null", String.class, ladder.meetupId());
        assertThat(storage.get(key)).isPresent();

        jdbc.update("update meetup_checkins set selfie_purge_due_at = now() - interval '1 minute' "
                + "where selfie_key = ?", key);
        purge.purge();

        assertThat(storage.get(key)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from meetup_checkins where selfie_key = ?", Integer.class,
                key)).isZero();
    }

    private Ladder confirmedMeetup() {
        return Ladder.start(port, mailpit, jdbc).videoVerified().withEmergencyContacts().proposed().confirmed();
    }
}
