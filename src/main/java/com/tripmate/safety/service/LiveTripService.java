package com.tripmate.safety.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.EmergencyContactService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.ratelimit.RateLimit;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.safety.entity.Checkin;
import com.tripmate.safety.entity.ItineraryShare;
import com.tripmate.safety.entity.RoundedLocation;
import com.tripmate.safety.entity.SosEvent;
import com.tripmate.safety.repository.CheckinRepository;
import com.tripmate.safety.repository.ItineraryShareRepository;
import com.tripmate.safety.repository.SafetyQueries;
import com.tripmate.safety.repository.SosEventRepository;
import com.tripmate.safety.service.LiveTripViews.Buddy;
import com.tripmate.safety.service.LiveTripViews.LastCheckIn;
import com.tripmate.safety.service.LiveTripViews.LiveTripView;
import com.tripmate.safety.service.LiveTripViews.NextCheckIn;
import com.tripmate.safety.service.LiveTripViews.OpenSos;
import com.tripmate.safety.service.LiveTripViews.Phase;
import com.tripmate.safety.service.LiveTripViews.ReportedLocation;
import com.tripmate.safety.service.LiveTripViews.Role;
import com.tripmate.safety.service.LiveTripViews.Sharing;
import com.tripmate.safety.service.LiveTripViews.TripRef;
import com.tripmate.trips.service.TripCrew;
import com.tripmate.trips.service.TripCrew.Crew;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Live trip mode: a traveller shares the trip with their emergency contact,
 * answers "are you safe?" check-ins, and can press SOS. Open to the host and
 * the companions still on a trip; anyone else gets the 404 an unknown trip
 * gets.
 *
 * <p>Every safety action is free. Nothing here can reach the trust score:
 * stopping sharing, missing a check-in and pressing SOS cost nothing, and an
 * SOS raises a moderation flag through the outbox. SOS is recorded first and
 * alerted second, so a failing SMS gateway can delay the alert (the job
 * retries it) but never lose the SOS.
 */
@Service
@EnableConfigurationProperties(SafetyProperties.class)
public class LiveTripService {

    static final String WRITE_BUCKET = "safety-write";

    static final String READ_BUCKET = "safety-read";

    static final String SOS_BUCKET = "safety-sos";

    private static final Logger log = LoggerFactory.getLogger(LiveTripService.class);

    private final AccountService accounts;

    private final TripCrew crews;

    private final EmergencyContactService emergencyContacts;

    private final ItineraryShareRepository shares;

    private final CheckinRepository checkins;

    private final SosEventRepository sosEvents;

    private final SafetyQueries queries;

    private final SafetyAlerts alerts;

    private final SafetyModerationEvents moderation;

    private final UserDataCipher cipher;

    private final RateLimiter rateLimiter;

    private final SafetyProperties properties;

    private final TransactionTemplate writing;

    private final TransactionTemplate reading;

    private final Clock clock;

    public LiveTripService(AccountService accounts, TripCrew crews, EmergencyContactService emergencyContacts,
                           ItineraryShareRepository shares, CheckinRepository checkins,
                           SosEventRepository sosEvents, SafetyQueries queries, SafetyAlerts alerts,
                           SafetyModerationEvents moderation, UserDataCipher cipher, RateLimiter rateLimiter,
                           SafetyProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.accounts = accounts;
        this.crews = crews;
        this.emergencyContacts = emergencyContacts;
        this.shares = shares;
        this.checkins = checkins;
        this.sosEvents = sosEvents;
        this.queries = queries;
        this.alerts = alerts;
        this.moderation = moderation;
        this.cipher = cipher;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.writing = new TransactionTemplate(transactionManager);
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.clock = clock;
    }

    /** The live trip screen in one call. */
    public LiveTripView view(SessionPrincipal caller, UUID tripId) {
        consume(READ_BUCKET, caller, properties.readLimit());
        return reading.execute(tx -> {
            accounts.requireActive(caller);
            return viewOf(requireTraveller(tripId, caller.userId()), caller.userId());
        });
    }

    /**
     * Starts sharing the trip with the caller's emergency contact, or changes
     * how often they are asked and the note the contact gets. The next
     * check-in is due one interval from now (or from the trip's start).
     */
    public LiveTripView share(SessionPrincipal caller, UUID tripId, int everyHours, String note) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        if (!ItineraryShare.INTERVALS.contains(everyHours)) {
            throw new ApiProblemException(SafetyProblem.INVALID_INTERVAL);
        }
        Optional<String> cleanNote;
        try {
            cleanNote = SafetyText.cleanNote(note);
        } catch (IllegalArgumentException tooLong) {
            throw new ApiProblemException(SafetyProblem.INVALID_NOTE);
        }
        UUID me = caller.userId();
        return write(caller, tripId, (crew, now) -> {
            requireLive(crew, now);
            if (!emergencyContacts.hasContact(me)) {
                throw new ApiProblemException(SafetyProblem.EMERGENCY_CONTACT_NEEDED);
            }
            byte[] sealedNote = cleanNote.map(text -> cipher.seal(me, EncryptedField.ITINERARY_NOTE,
                    Locations.noteContext(tripId), text.getBytes(StandardCharsets.UTF_8))).orElse(null);
            ItineraryShare share = shares.findByTripIdAndUserId(tripId, me)
                    .map(existing -> {
                        existing.restart(everyHours, sealedNote, now);
                        return existing;
                    })
                    .orElseGet(() -> ItineraryShare.start(tripId, me, everyHours, sealedNote, now));
            shares.save(share);
            Instant due = LiveWindow.nextDue(crew, now, share.interval());
            Optional<Checkin> pending = checkins.findFirstByTripIdAndUserIdAndStatus(tripId, me,
                    Checkin.Status.PENDING);
            if (pending.isPresent()) {
                if (due == null) {
                    pending.get().cancel();
                } else {
                    pending.get().reschedule(due);
                }
                checkins.save(pending.get());
            } else if (due != null) {
                checkins.save(Checkin.due(tripId, me, due, now));
            }
        });
    }

    /** Stops sharing. Free, needs no reason, and cancels the check-in waiting. */
    public LiveTripView stopSharing(SessionPrincipal caller, UUID tripId) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        UUID me = caller.userId();
        return write(caller, tripId, (crew, now) -> {
            shares.findByTripIdAndUserId(tripId, me).filter(ItineraryShare::isActive).ifPresent(share -> {
                share.stop(now);
                shares.save(share);
            });
            checkins.findFirstByTripIdAndUserIdAndStatus(tripId, me, Checkin.Status.PENDING).ifPresent(pending -> {
                pending.cancel();
                checkins.save(pending);
            });
        });
    }

    /**
     * "I'm safe": answers the check-in waiting (or records one now, early) and
     * schedules the next. A location is optional; only a rounded point is
     * kept in the clear.
     */
    public LiveTripView checkIn(SessionPrincipal caller, UUID tripId, ReportedLocation location) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        requireValid(location);
        UUID me = caller.userId();
        return write(caller, tripId, (crew, now) -> {
            requireLive(crew, now);
            ItineraryShare share = shares.findByTripIdAndUserId(tripId, me).filter(ItineraryShare::isActive)
                    .orElseThrow(() -> new ApiProblemException(SafetyProblem.NOT_SHARING));
            RoundedLocation sealed = location == null ? null : Locations.seal(cipher, me,
                    EncryptedField.CHECKIN_LOCATION, Locations.checkinContext(tripId, me, now), location);
            Checkin answered = checkins.findFirstByTripIdAndUserIdAndStatus(tripId, me, Checkin.Status.PENDING)
                    .orElseGet(() -> Checkin.due(tripId, me, now, now));
            answered.confirm(now, sealed);
            checkins.saveAndFlush(answered);
            Instant due = LiveWindow.nextDue(crew, now, share.interval());
            if (due != null) {
                checkins.save(Checkin.due(tripId, me, due, now));
            }
        });
    }

    /**
     * SOS. Recorded and flagged for moderation in one transaction, then the
     * emergency contact is alerted in another. Pressing again with the same
     * client ID (a retry) changes nothing. Allowed to anyone on the trip, at
     * any time, sharing or not.
     */
    public LiveTripView raiseSos(SessionPrincipal caller, UUID tripId, UUID clientSosId, ReportedLocation location) {
        consume(SOS_BUCKET, caller, properties.sosLimit());
        requireValid(location);
        UUID me = caller.userId();
        UUID sosId = writing.execute(tx -> {
            accounts.lockActive(caller);
            requireTraveller(tripId, me);
            Optional<SosEvent> earlier = sosEvents.findByUserIdAndClientSosId(me, clientSosId);
            if (earlier.isPresent()) {
                return earlier.get().getId();
            }
            RoundedLocation sealed = location == null ? null : Locations.seal(cipher, me,
                    EncryptedField.SOS_LOCATION, Locations.sosContext(me, clientSosId), location);
            SosEvent sos = sosEvents.save(SosEvent.raise(tripId, me, clientSosId, sealed, clock.instant()));
            moderation.sosRaised(sos.getId(), tripId, me);
            return sos.getId();
        });
        deliverSos(sosId);
        return reading.execute(tx -> viewOf(requireTraveller(tripId, me), me));
    }

    /** The traveller is safe again. Only their own SOS; anyone else gets 404. */
    public LiveTripView resolveSos(SessionPrincipal caller, UUID sosId) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        UUID me = caller.userId();
        UUID tripId = writing.execute(tx -> {
            accounts.requireActive(caller);
            SosEvent sos = sosEvents.findById(sosId).filter(found -> found.getUserId().equals(me))
                    .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
            sos.resolve(clock.instant());
            sosEvents.save(sos);
            return sos.getTripId();
        });
        return reading.execute(tx -> crews.of(tripId).filter(crew -> crew.everTravelled(me))
                .map(crew -> viewOf(crew, me))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND)));
    }

    /**
     * One attempt at the SOS alert, under the row's lock so two app instances
     * never send it twice. A failed send still counts as an attempt.
     */
    public boolean deliverSos(UUID sosId) {
        try {
            return Boolean.TRUE.equals(writing.execute(tx -> sosEvents.lockIfOpen(sosId).map(sos -> {
                sos.recordAlertAttempt();
                boolean sent = alerts.sos(sos, shares.findByTripIdAndUserId(sos.getTripId(), sos.getUserId())
                        .filter(ItineraryShare::isActive));
                if (sent) {
                    sos.escalated(clock.instant());
                }
                sosEvents.save(sos);
                return sent;
            }).orElse(false)));
        } catch (RuntimeException e) {
            log.warn("SOS alert failed and will be retried: {}", e.getClass().getSimpleName());
            writing.executeWithoutResult(tx -> sosEvents.findById(sosId).ifPresent(sos -> {
                sos.recordAlertAttempt();
                sosEvents.save(sos);
            }));
            return false;
        }
    }

    private LiveTripView write(SessionPrincipal caller, UUID tripId, Change change) {
        UUID me = caller.userId();
        writing.executeWithoutResult(tx -> {
            accounts.lockActive(caller);
            Crew crew = requireTraveller(tripId, me);
            if (!crew.isLocked()) {
                throw new ApiProblemException(SafetyProblem.TRIP_NOT_LOCKED);
            }
            change.apply(crew, clock.instant());
        });
        return reading.execute(tx -> viewOf(requireTraveller(tripId, me), me));
    }

    private static void requireLive(Crew crew, Instant now) {
        if (LiveWindow.phase(crew, now) == Phase.ENDED) {
            throw new ApiProblemException(SafetyProblem.TRIP_OVER);
        }
    }

    private static void requireValid(ReportedLocation location) {
        if (location != null && (!Double.isFinite(location.latitude()) || !Double.isFinite(location.longitude())
                || Math.abs(location.latitude()) > 90 || Math.abs(location.longitude()) > 180
                || (location.accuracyMeters() != null && (!Double.isFinite(location.accuracyMeters())
                || location.accuracyMeters() < 0 || location.accuracyMeters() > 100_000)))) {
            throw new ApiProblemException(SafetyProblem.INVALID_LOCATION);
        }
    }

    /** The host or a companion still on the trip; the 404 an unknown trip gets for anyone else. */
    private Crew requireTraveller(UUID tripId, UUID userId) {
        return crews.of(tripId).filter(crew -> crew.isTraveller(userId))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
    }

    private LiveTripView viewOf(Crew crew, UUID me) {
        Instant now = clock.instant();
        UUID tripId = crew.tripId();
        Optional<ItineraryShare> myShare = shares.findByTripIdAndUserId(tripId, me).filter(ItineraryShare::isActive);
        Sharing sharing = myShare.map(share -> new Sharing(share.getCheckInEveryHours(),
                share.getNoteCiphertext() != null, share.getStartedAt())).orElse(null);
        NextCheckIn next = checkins.findFirstByTripIdAndUserIdAndStatus(tripId, me, Checkin.Status.PENDING)
                .map(pending -> new NextCheckIn(pending.getScheduledAt(), !pending.getScheduledAt().isAfter(now)))
                .orElse(null);
        LastCheckIn last = lastAnswered(tripId, me).map(checkin -> new LastCheckIn(checkin.getRespondedAt(),
                checkin.getRoundedLatitude() == null ? null : BigDecimal.valueOf(Locations.distanceKm(
                        checkin.getRoundedLatitude(), checkin.getRoundedLongitude(), crew.destinationLatitude(),
                        crew.destinationLongitude())).setScale(1, RoundingMode.HALF_UP).doubleValue()))
                .orElse(null);
        Map<UUID, String> names = queries.firstNames(crew.travellers());
        Map<UUID, Boolean> sharingByUser = new HashMap<>();
        shares.findByTripId(tripId).forEach(share -> sharingByUser.put(share.getUserId(), share.isActive()));
        List<Buddy> buddies = new ArrayList<>();
        for (UUID buddy : crew.travellers()) {
            if (!buddy.equals(me)) {
                buddies.add(new Buddy(names.getOrDefault(buddy, "Traveller"),
                        crew.isHost(buddy) ? Role.HOST : Role.COMPANION, sharingByUser.getOrDefault(buddy, false),
                        lastAnswered(tripId, buddy).map(Checkin::getRespondedAt).orElse(null)));
            }
        }
        OpenSos openSos = sosEvents.findFirstByTripIdAndUserIdAndStatusNotOrderByTriggeredAtDesc(tripId, me,
                        SosEvent.Status.RESOLVED)
                .map(sos -> new OpenSos(sos.getId(), sos.getTriggeredAt(), sos.getEscalatedAt() != null))
                .orElse(null);
        return new LiveTripView(new TripRef(tripId, crew.title(), crew.destinationName(),
                crew.destinationLatitude(), crew.destinationLongitude(), crew.startDate(), crew.endDate()),
                LiveWindow.phase(crew, now), crew.isLocked(), emergencyContacts.hasContact(me), sharing, next, last,
                buddies, openSos);
    }

    private Optional<Checkin> lastAnswered(UUID tripId, UUID userId) {
        return checkins.findFirstByTripIdAndUserIdAndStatusOrderByRespondedAtDesc(tripId, userId,
                Checkin.Status.CONFIRMED);
    }

    private void consume(String bucket, SessionPrincipal caller, RateLimit limit) {
        rateLimiter.consume(bucket, caller.userId().toString(), limit);
    }

    @FunctionalInterface
    private interface Change {
        void apply(Crew crew, Instant now);
    }
}
