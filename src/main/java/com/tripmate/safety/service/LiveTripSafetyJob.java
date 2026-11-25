package com.tripmate.safety.service;

import com.tripmate.identity.service.ContactDirectory;
import com.tripmate.safety.entity.Checkin;
import com.tripmate.safety.entity.ItineraryShare;
import com.tripmate.safety.repository.CheckinRepository;
import com.tripmate.safety.repository.ItineraryShareRepository;
import com.tripmate.safety.repository.SafetyQueries;
import com.tripmate.safety.repository.SosEventRepository;
import com.tripmate.trips.service.TripCrew;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The safety net under live trips. For every check-in that has come due:
 * <ul>
 *   <li>at its due time, an email asks the traveller to check in;</li>
 *   <li>{@code escalate-after} later, still unanswered, their emergency
 *       contact is alerted, the check-in becomes MISSED and the next one is
 *       scheduled, so the net stays up.</li>
 * </ul>
 * It also retries SOS alerts that could not be delivered at once.
 *
 * <p>Each check-in is handled in its own transaction under
 * {@code FOR UPDATE SKIP LOCKED}, so several app instances can run this
 * without double-alerting, and a failed alert rolls back only that one, to
 * be retried next run. A missed check-in is never a trust score input.
 */
@Component
public class LiveTripSafetyJob {

    private static final Logger log = LoggerFactory.getLogger(LiveTripSafetyJob.class);

    private final CheckinRepository checkins;

    private final ItineraryShareRepository shares;

    private final SosEventRepository sosEvents;

    private final TripCrew crews;

    private final ContactDirectory contacts;

    private final SafetyQueries queries;

    private final SafetyAlerts alerts;

    private final SafetyMailer mailer;

    private final LiveTripService liveTrips;

    private final SafetyProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public LiveTripSafetyJob(CheckinRepository checkins, ItineraryShareRepository shares,
                             SosEventRepository sosEvents, TripCrew crews, ContactDirectory contacts,
                             SafetyQueries queries, SafetyAlerts alerts, SafetyMailer mailer,
                             LiveTripService liveTrips, SafetyProperties properties,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.checkins = checkins;
        this.shares = shares;
        this.sosEvents = sosEvents;
        this.crews = crews;
        this.contacts = contacts;
        this.queries = queries;
        this.alerts = alerts;
        this.mailer = mailer;
        this.liveTrips = liveTrips;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.safety.job-interval}", initialDelayString = "${tripmate.safety.job-interval}")
    public void run() {
        sweep();
    }

    /** One pass: undelivered SOS alerts first, then due check-ins. */
    public void sweep() {
        for (UUID sosId : sosEvents.findUnalerted(properties.maxSosAlertAttempts(), properties.jobBatch())) {
            liveTrips.deliverSos(sosId);
        }
        List<UUID> due = checkins.findPendingDueBefore(clock.instant(), properties.jobBatch());
        for (UUID checkinId : due) {
            try {
                transactions.executeWithoutResult(tx -> checkins.lockIfPending(checkinId)
                        .ifPresent(checkin -> handle(checkin, clock.instant())));
            } catch (RuntimeException e) {
                log.warn("Trip check-in step failed and will be retried: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void handle(Checkin checkin, Instant now) {
        ItineraryShare share = shares.findByTripIdAndUserId(checkin.getTripId(), checkin.getUserId())
                .filter(ItineraryShare::isActive).orElse(null);
        if (share == null) {
            checkin.cancel();
            checkins.save(checkin);
            return;
        }
        if (!now.isBefore(checkin.getScheduledAt().plus(properties.escalateAfter()))) {
            boolean alerted = alerts.missedCheckin(checkin, checkins.findFirstByTripIdAndUserIdAndStatusOrderByRespondedAtDesc(
                    checkin.getTripId(), checkin.getUserId(), Checkin.Status.CONFIRMED), share);
            if (!alerted) {
                log.warn("A missed trip check-in had no emergency contact or active account to alert");
            }
            checkin.miss(now);
            checkins.saveAndFlush(checkin);
            crews.of(checkin.getTripId())
                    .map(crew -> LiveWindow.nextDue(crew, now, share.interval()))
                    .ifPresent(next -> checkins.save(Checkin.due(checkin.getTripId(), checkin.getUserId(), next, now)));
        } else if (checkin.getPingedAt() == null) {
            contacts.contactOf(checkin.getUserId()).ifPresent(account -> mailer.sendCheckInPing(account.email(),
                    queries.firstNames(List.of(checkin.getUserId())).getOrDefault(checkin.getUserId(), "there"),
                    crews.of(checkin.getTripId()).map(TripCrew.Crew::title).orElse("your trip"),
                    properties.escalateAfter().toMinutes()));
            checkin.recordPing(now);
            checkins.save(checkin);
        }
    }
}
