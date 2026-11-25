package com.tripmate.meetup.service;

import com.tripmate.identity.emergency.EmergencyAlertSender;
import com.tripmate.identity.service.ContactDirectory;
import com.tripmate.identity.service.EmergencyContactService;
import com.tripmate.meetup.entity.Meetup;
import com.tripmate.meetup.entity.MeetupCheckin;
import com.tripmate.meetup.entity.Venue;
import com.tripmate.meetup.repository.MeetupCheckinRepository;
import com.tripmate.meetup.repository.MeetupQueries;
import com.tripmate.meetup.repository.MeetupRepository;
import com.tripmate.meetup.repository.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The safety net under every confirmed meet-up. For each person who has
 * neither checked in nor said they are safe:
 * <ul>
 *   <li>{@code ping-after} (30 minutes) past the start, an email asks them to
 *       check in or tap "I'm safe";</li>
 *   <li>{@code escalate-after} (60 minutes) past the start, their emergency
 *       contact is alerted;</li>
 *   <li>when check-in closes, a meet-up that did not complete becomes a
 *       no-show, and whoever did not arrive is a trust input.</li>
 * </ul>
 *
 * <p>Each meet-up is handled in its own transaction under a
 * {@code FOR UPDATE SKIP LOCKED} lock, so several app instances can run this
 * at once without double-sending, and a failed alert rolls back only that
 * meet-up, to be retried on the next run: an alert is recorded only once it
 * was handed over. The other person is never told about either step.
 */
@Component
public class MeetupSafetyJob {

    private static final Logger log = LoggerFactory.getLogger(MeetupSafetyJob.class);

    private static final DateTimeFormatter ALERT_TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    private final MeetupRepository meetups;

    private final MeetupCheckinRepository checkins;

    private final VenueRepository venues;

    private final MeetupQueries queries;

    private final ContactDirectory contacts;

    private final EmergencyContactService emergencyContacts;

    private final EmergencyAlertSender alerts;

    private final MeetupMailer mailer;

    private final MeetupTrustEvents trustEvents;

    private final MeetupProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public MeetupSafetyJob(MeetupRepository meetups, MeetupCheckinRepository checkins, VenueRepository venues,
                           MeetupQueries queries, ContactDirectory contacts, EmergencyContactService emergencyContacts,
                           EmergencyAlertSender alerts, MeetupMailer mailer, MeetupTrustEvents trustEvents,
                           MeetupProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.meetups = meetups;
        this.checkins = checkins;
        this.venues = venues;
        this.queries = queries;
        this.contacts = contacts;
        this.emergencyContacts = emergencyContacts;
        this.alerts = alerts;
        this.mailer = mailer;
        this.trustEvents = trustEvents;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.meetup.job-interval}",
            initialDelayString = "${tripmate.meetup.job-interval}")
    public void run() {
        sweep();
    }

    /** One pass over every confirmed meet-up past its ping time. */
    public void sweep() {
        Instant now = clock.instant();
        List<UUID> due = meetups.findConfirmedStartedBefore(now.minus(properties.pingAfter()), properties.jobBatch());
        for (UUID meetupId : due) {
            try {
                transactions.executeWithoutResult(tx -> meetups.lockIfConfirmed(meetupId)
                        .ifPresent(meetup -> handle(meetup, clock.instant())));
            } catch (RuntimeException e) {
                log.warn("Meet-up safety step failed and will be retried: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void handle(Meetup meetup, Instant now) {
        Instant start = meetup.getScheduledAt();
        List<MeetupCheckin> people = checkins.findByMeetupId(meetup.getId());
        if (!now.isBefore(start.plus(properties.checkinClosesAfter()))) {
            meetup.markNoShow(now);
            meetups.save(meetup);
            people.stream().filter(person -> !person.hasArrived())
                    .forEach(person -> trustEvents.noShow(person.getUserId(), meetup.getId()));
            return;
        }
        Venue venue = venues.findById(meetup.getVenueId()).orElseThrow();
        for (MeetupCheckin person : people) {
            if (person.isAccountedFor()) {
                continue;
            }
            if (person.getEscalatedAt() == null && !now.isBefore(start.plus(properties.escalateAfter()))) {
                escalate(meetup, venue, person, now);
            } else if (person.getPingedAt() == null) {
                contacts.contactOf(person.getUserId()).ifPresent(contact -> mailer.sendPing(contact.email(),
                        firstName(person.getUserId()), meetup.getVenueName(), start, venue.getZone()));
                person.recordPing(now);
                checkins.save(person);
            }
        }
    }

    private void escalate(Meetup meetup, Venue venue, MeetupCheckin person, Instant now) {
        var account = contacts.contactOf(person.getUserId());
        var contact = emergencyContacts.forAlert(person.getUserId());
        if (account.isPresent() && contact.isPresent()) {
            String name = firstName(person.getUserId());
            alerts.send(contact.get(), new EmergencyAlertSender.Alert(name, account.get().email(),
                    "%s planned to meet someone at %s (%s) at %s and has not checked in or answered us.".formatted(
                            name, meetup.getVenueName(), venue.getAddress(),
                            ALERT_TIME.format(meetup.getScheduledAt().atZone(venue.getZone())))));
            meetup.recordEmergencyContactNotified(now);
            meetups.save(meetup);
        } else {
            log.warn("A meet-up escalation had no emergency contact or active account to use");
        }
        person.recordEscalation(now);
        checkins.save(person);
    }

    private String firstName(UUID userId) {
        return queries.person(userId).map(MeetupQueries.Person::firstName).orElse("Your TripMates contact");
    }
}
