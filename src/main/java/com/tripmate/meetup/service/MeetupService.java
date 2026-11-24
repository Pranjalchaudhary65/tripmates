package com.tripmate.meetup.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.EmergencyContactService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.identity.upload.ImageSanitizer;
import com.tripmate.identity.upload.MediaKind;
import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.meetup.entity.Meetup;
import com.tripmate.meetup.entity.MeetupCancellation;
import com.tripmate.meetup.entity.MeetupCheckin;
import com.tripmate.meetup.entity.MeetupFeedback;
import com.tripmate.meetup.entity.MeetupStatus;
import com.tripmate.meetup.entity.Venue;
import com.tripmate.meetup.entity.VideoCallConfirmation;
import com.tripmate.meetup.repository.MeetupCheckinRepository;
import com.tripmate.meetup.repository.MeetupFeedbackRepository;
import com.tripmate.meetup.repository.MeetupQueries;
import com.tripmate.meetup.repository.MeetupRepository;
import com.tripmate.meetup.repository.VenueRepository;
import com.tripmate.meetup.repository.VideoCallConfirmationRepository;
import com.tripmate.meetup.service.MeetupViews.CheckInResult;
import com.tripmate.meetup.service.MeetupViews.MatchView;
import com.tripmate.meetup.service.MeetupViews.MeetupView;
import com.tripmate.meetup.service.MeetupViews.Person;
import com.tripmate.meetup.service.MeetupViews.Role;
import com.tripmate.meetup.service.MeetupViews.Stage;
import com.tripmate.meetup.service.MeetupViews.TripRef;
import com.tripmate.meetup.service.MeetupViews.VenueOption;
import com.tripmate.meetup.service.MeetupViews.VenueRef;
import com.tripmate.meetup.service.MeetupViews.VideoCall;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.trips.service.TripMembership;
import com.tripmate.trips.service.TripMembership.Match;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The public meet-up rung of the trust ladder: confirm the video call,
 * propose and confirm a daylight meet-up at an allowlisted venue, check in
 * there with location and selfie, then say privately how it felt.
 *
 * <p>Every action names the match (the accepted join request's ID) and is
 * open only to its host and its companion; anyone else gets the 404 an
 * unknown ID gets. Every change to one match runs under its trip_members row lock,
 * so two people acting at once (both rating, a confirm racing a cancel)
 * queue up instead of interleaving.
 *
 * <p>The gate this module owns: the trip locks for a companion only when a
 * meet-up completed and both comfort ratings are at or above the threshold.
 * A rating below it, or a safety cancellation by either person, stops the
 * match: the companion's place is released and the other person is told only
 * that the match did not proceed. Neither path records anything against the
 * person who chose it.
 */
@Service
@EnableConfigurationProperties(MeetupProperties.class)
public class MeetupService {

    static final String WRITE_BUCKET = "meetup-write";

    static final String READ_BUCKET = "meetup-read";

    static final int MAX_VENUES = 20;

    static final int MAX_NOTES_LENGTH = 1000;

    private final AccountService accounts;

    private final TripMembership memberships;

    private final EmergencyContactService emergencyContacts;

    private final VideoCallConfirmationRepository videoCalls;

    private final MeetupRepository meetups;

    private final MeetupCheckinRepository checkins;

    private final MeetupFeedbackRepository feedback;

    private final VenueRepository venues;

    private final MeetupQueries queries;

    private final DaylightRules daylight;

    private final MeetupTrustEvents trustEvents;

    private final ImageSanitizer images;

    private final ObjectStorage storage;

    private final UserDataCipher cipher;

    private final RateLimiter rateLimiter;

    private final MeetupProperties properties;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Clock clock;

    public MeetupService(AccountService accounts, TripMembership memberships,
                         EmergencyContactService emergencyContacts, VideoCallConfirmationRepository videoCalls,
                         MeetupRepository meetups, MeetupCheckinRepository checkins, MeetupFeedbackRepository feedback,
                         VenueRepository venues, MeetupQueries queries, DaylightRules daylight,
                         MeetupTrustEvents trustEvents, ImageSanitizer images, ObjectStorage storage,
                         UserDataCipher cipher, RateLimiter rateLimiter, MeetupProperties properties,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.accounts = accounts;
        this.memberships = memberships;
        this.emergencyContacts = emergencyContacts;
        this.videoCalls = videoCalls;
        this.meetups = meetups;
        this.checkins = checkins;
        this.feedback = feedback;
        this.venues = venues;
        this.queries = queries;
        this.daylight = daylight;
        this.trustEvents = trustEvents;
        this.images = images;
        this.storage = storage;
        this.cipher = cipher;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /** The match screen in one call: where the ladder stands and what the caller can do next. */
    public MatchView view(SessionPrincipal caller, UUID matchId) {
        consume(READ_BUCKET, caller, properties.readLimit());
        return readOnly.execute(tx -> {
            accounts.requireActive(caller);
            return viewOf(requireMatch(caller, matchId), caller.userId());
        });
    }

    /** Allowlisted venues near a point (the trip's destination by default), with their daylight windows. */
    public List<VenueOption> venues(SessionPrincipal caller, UUID matchId, Double latitude, Double longitude,
                                    Double radiusKm) {
        consume(READ_BUCKET, caller, properties.readLimit());
        return readOnly.execute(tx -> {
            accounts.requireActive(caller);
            Match match = requireMatch(caller, matchId);
            double lat = latitude == null ? match.destinationLatitude() : latitude;
            double lon = longitude == null ? match.destinationLongitude() : longitude;
            double radius = radiusKm == null ? properties.venueRadiusKm() : radiusKm;
            Instant now = clock.instant();
            return queries.venuesNear(lat, lon, radius, MAX_VENUES).stream()
                    .map(card -> new VenueOption(new VenueRef(card.id(), card.name(), card.address(), card.city(),
                            card.category(), card.latitude(), card.longitude(), card.zone().getId()), card.distanceKm(),
                            card.zone().getId(),
                            daylight.upcoming(card.latitude(), card.longitude(), card.zone(), now,
                                    match.tripStartDate())))
                    .toList();
        });
    }

    /** The caller's side of the video call. Once both sides confirm, the trip records it. */
    public MatchView confirmVideoCall(SessionPrincipal caller, UUID matchId) {
        return write(caller, matchId, (match, now) -> {
            requireAccepted(match);
            VideoCallConfirmation confirmation = confirmationRow(match.memberId(), now);
            confirmation.confirm(match.isHost(caller.userId()), now);
            videoCalls.save(confirmation);
            if (confirmation.isComplete() && match.videoVerifiedAt() == null) {
                memberships.confirmVideoCall(match.memberId(), now);
            }
        });
    }

    public MatchView propose(SessionPrincipal caller, UUID matchId, UUID venueId, Instant scheduledAt,
                             boolean plusOne) {
        return write(caller, matchId, (match, now) -> {
            requireAccepted(match);
            if (match.videoVerifiedAt() == null) {
                throw new ApiProblemException(MeetupProblem.VIDEO_CALL_NEEDED);
            }
            if (!emergencyContacts.hasContact(caller.userId())) {
                throw new ApiProblemException(MeetupProblem.EMERGENCY_CONTACT_NEEDED);
            }
            Optional<Meetup> latest = meetups.findFirstByMemberIdOrderByCreatedAtDesc(match.memberId());
            if (latest.map(meetup -> meetup.getStatus().isActive()
                    || meetup.getStatus() == MeetupStatus.COMPLETED).orElse(false)) {
                throw new ApiProblemException(MeetupProblem.MEETUP_EXISTS);
            }
            Venue venue = venues.findByIdAndActiveTrue(venueId)
                    .orElseThrow(() -> new ApiProblemException(MeetupProblem.VENUE_UNAVAILABLE));
            requireAllowedStart(venue, scheduledAt, now, match.tripStartDate());
            meetups.save(Meetup.propose(match.tripId(), match.memberId(), match.hostUserId(),
                    match.companionUserId(), caller.userId(), venue, scheduledAt, plusOne, now));
        });
    }

    /** The other person agrees. From here both are expected at the venue, and the safety job watches. */
    public MatchView confirm(SessionPrincipal caller, UUID matchId, boolean plusOne) {
        return write(caller, matchId, (match, now) -> {
            requireAccepted(match);
            Meetup meetup = activeMeetup(match);
            if (meetup.getStatus() != MeetupStatus.PROPOSED) {
                throw new ApiProblemException(MeetupProblem.NO_MEETUP);
            }
            if (meetup.getProposedByUserId().equals(caller.userId())) {
                throw new ApiProblemException(MeetupProblem.OWN_PROPOSAL);
            }
            if (!emergencyContacts.hasContact(caller.userId())) {
                throw new ApiProblemException(MeetupProblem.EMERGENCY_CONTACT_NEEDED);
            }
            if (!meetup.getScheduledAt().isAfter(now)) {
                throw new ApiProblemException(MeetupProblem.MEETUP_PASSED);
            }
            meetup.confirm(caller.userId(), plusOne, now);
            meetups.save(meetup);
            checkins.save(MeetupCheckin.expect(meetup.getId(), meetup.getHostUserId(), now));
            checkins.save(MeetupCheckin.expect(meetup.getId(), meetup.getRequesterUserId(), now));
        });
    }

    /**
     * Calls the meet-up off. SAFETY needs no reason, costs nothing, and ends
     * the match for both; it never reaches the trust score. CONVENIENCE
     * leaves the match open for another proposal, and only a confirmed
     * meet-up cancelled close to its start is a trust input.
     */
    public MatchView cancel(SessionPrincipal caller, UUID matchId, MeetupCancellation type) {
        return write(caller, matchId, (match, now) -> {
            requireAccepted(match);
            Meetup meetup = activeMeetup(match);
            boolean wasConfirmed = meetup.getStatus() == MeetupStatus.CONFIRMED;
            meetup.cancel(caller.userId(), type, now);
            meetups.save(meetup);
            if (type == MeetupCancellation.SAFETY) {
                memberships.releaseForSafety(match.memberId(), now);
            } else if (wasConfirmed && meetup.getScheduledAt().minus(properties.lateCancelWithin()).isBefore(now)) {
                trustEvents.lateConvenienceCancel(caller.userId(), meetup.getId());
            }
        });
    }

    /**
     * Arrival: a reported point within the geofence and a selfie taken now,
     * both or nothing. The point is checked against the venue in the
     * database; a point outside it stores nothing at all.
     */
    public CheckInResult checkIn(SessionPrincipal caller, UUID matchId, double latitude, double longitude,
                                 Double accuracyMeters, byte[] selfie) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90
                || Math.abs(longitude) > 180 || (accuracyMeters != null && (!Double.isFinite(accuracyMeters)
                || accuracyMeters < 0))) {
            throw new ApiProblemException(MeetupProblem.INVALID_LOCATION);
        }
        if (accuracyMeters != null && accuracyMeters > properties.maxLocationAccuracyMeters()) {
            throw new ApiProblemException(MeetupProblem.LOCATION_TOO_ROUGH);
        }
        byte[] jpeg = images.sanitize(selfie).jpeg();
        return transactions.execute(tx -> {
            accounts.lockActive(caller);
            Match match = lockMatch(caller, matchId);
            Instant now = clock.instant();
            requireAccepted(match);
            Meetup meetup = activeMeetup(match);
            if (meetup.getStatus() != MeetupStatus.CONFIRMED) {
                throw new ApiProblemException(MeetupProblem.NO_MEETUP);
            }
            if (now.isBefore(meetup.getScheduledAt().minus(properties.checkinOpensBefore()))
                    || now.isAfter(meetup.getScheduledAt().plus(properties.checkinClosesAfter()))) {
                throw new ApiProblemException(MeetupProblem.CHECK_IN_CLOSED);
            }
            MeetupCheckin mine = checkins.findByMeetupIdAndUserId(meetup.getId(), caller.userId()).orElseThrow();
            if (mine.hasArrived()) {
                throw new ApiProblemException(MeetupProblem.ALREADY_CHECKED_IN);
            }
            double distance = queries.distanceFromVenue(meetup.getId(), latitude, longitude);
            if (distance > MeetupProperties.GEOFENCE_METERS) {
                throw new ApiProblemException(MeetupProblem.NOT_AT_VENUE);
            }
            String key = MediaKind.MEETUP_SELFIE.newKey();
            storage.put(key, jpeg);
            deleteOnRollback(key);
            mine.arrive(latitude, longitude, BigDecimal.valueOf(distance).setScale(1, RoundingMode.HALF_UP), key,
                    now.plus(properties.selfieRetention()), now);
            checkins.save(mine);
            boolean bothHere = checkins.findByMeetupId(meetup.getId()).stream().allMatch(MeetupCheckin::hasArrived);
            if (bothHere) {
                meetup.complete(now);
                meetups.save(meetup);
            }
            return new CheckInResult(true, bothHere);
        });
    }

    /** "I'm safe": the safety job stops asking and does not alert anyone. Arrival is not implied. */
    public MatchView markSafe(SessionPrincipal caller, UUID matchId) {
        return write(caller, matchId, (match, now) -> {
            Meetup meetup = activeMeetup(match);
            if (meetup.getStatus() != MeetupStatus.CONFIRMED) {
                throw new ApiProblemException(MeetupProblem.NO_MEETUP);
            }
            MeetupCheckin mine = checkins.findByMeetupIdAndUserId(meetup.getId(), caller.userId()).orElseThrow();
            mine.confirmSafe(now);
            checkins.save(mine);
        });
    }

    /**
     * The private comfort rating, once per person per completed meet-up. It
     * is used for exactly one decision, here, and nothing else ever reads it.
     */
    public MatchView rate(SessionPrincipal caller, UUID matchId, int comfortRating, String notes) {
        return write(caller, matchId, (match, now) -> {
            requireAccepted(match);
            Meetup meetup = meetups.findFirstByMemberIdOrderByCreatedAtDesc(match.memberId())
                    .filter(latest -> latest.getStatus() == MeetupStatus.COMPLETED)
                    .orElseThrow(() -> new ApiProblemException(MeetupProblem.FEEDBACK_NOT_OPEN));
            if (feedback.existsByMeetupIdAndUserId(meetup.getId(), caller.userId())) {
                throw new ApiProblemException(MeetupProblem.FEEDBACK_GIVEN);
            }
            byte[] sealedNotes = notes == null || notes.isBlank() ? null
                    : cipher.seal(caller.userId(), EncryptedField.MEETUP_PRIVATE_NOTE, meetup.getId().toString(),
                    notes.strip().getBytes(StandardCharsets.UTF_8));
            feedback.save(MeetupFeedback.record(meetup.getId(), caller.userId(), comfortRating, sealedNotes, now));
            decideAfterFeedback(match, meetup, now);
        });
    }

    /**
     * The trip-lock gate. Any rating below the threshold stops the match at
     * once; the trip locks only when both ratings are in and both pass.
     */
    private void decideAfterFeedback(Match match, Meetup meetup, Instant now) {
        List<MeetupFeedback> ratings = feedback.findByMeetupId(meetup.getId());
        boolean anyBelow = ratings.stream().anyMatch(rating -> rating.getComfortRating()
                < properties.comfortThreshold());
        if (anyBelow) {
            memberships.releaseForSafety(match.memberId(), now);
        } else if (ratings.size() == 2) {
            memberships.lock(match.memberId(), now);
        }
    }

    private MatchView write(SessionPrincipal caller, UUID matchId, Change change) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        transactions.executeWithoutResult(tx -> {
            accounts.lockActive(caller);
            change.apply(lockMatch(caller, matchId), clock.instant());
        });
        return readOnly.execute(tx -> viewOf(requireMatch(caller, matchId), caller.userId()));
    }

    private MatchView viewOf(Match match, UUID me) {
        Person other = queries.person(match.otherThan(me))
                .map(person -> new Person(person.firstName(), person.verificationTier()))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
        Role role = match.isHost(me) ? Role.HOST : Role.COMPANION;
        TripRef trip = new TripRef(match.tripId(), match.tripTitle(), match.destinationName(), match.tripStartDate());
        if (match.didNotProceed()) {
            return new MatchView(match.memberId(), role, trip, other, Stage.DID_NOT_PROCEED, null, false, null,
                    false);
        }
        boolean host = match.isHost(me);
        Optional<VideoCallConfirmation> confirmation = videoCalls.findById(match.memberId());
        VideoCall videoCall = new VideoCall(confirmation.map(c -> c.isConfirmedBy(host)).orElse(false),
                confirmation.map(c -> c.isConfirmedBy(!host)).orElse(false), match.videoVerifiedAt() != null);
        Optional<Meetup> latest = meetups.findFirstByMemberIdOrderByCreatedAtDesc(match.memberId());
        boolean myFeedbackGiven = latest.filter(meetup -> meetup.getStatus() == MeetupStatus.COMPLETED)
                .map(meetup -> feedback.existsByMeetupIdAndUserId(meetup.getId(), me))
                .orElse(false);
        Stage stage = stageOf(match, videoCall, latest.orElse(null), myFeedbackGiven);
        return new MatchView(match.memberId(), role, trip, other, stage, videoCall,
                emergencyContacts.hasContact(me), latest.map(meetup -> meetupView(meetup, me)).orElse(null),
                myFeedbackGiven);
    }

    private static Stage stageOf(Match match, VideoCall videoCall, Meetup latest, boolean myFeedbackGiven) {
        if (match.isLocked()) {
            return Stage.LOCKED;
        }
        if (!match.isAccepted()) {
            return Stage.CLOSED;
        }
        if (!videoCall.verified()) {
            return Stage.VIDEO_CALL;
        }
        if (latest == null) {
            return Stage.ARRANGE_MEETUP;
        }
        return switch (latest.getStatus()) {
            case PROPOSED -> Stage.AWAITING_CONFIRMATION;
            case CONFIRMED -> Stage.CONFIRMED;
            case COMPLETED -> myFeedbackGiven ? Stage.WAITING_FOR_OTHER : Stage.FEEDBACK;
            case CANCELLED, NO_SHOW -> Stage.ARRANGE_MEETUP;
        };
    }

    private MeetupView meetupView(Meetup meetup, UUID me) {
        Venue venue = venues.findById(meetup.getVenueId()).orElseThrow();
        List<MeetupCheckin> arrivals = checkins.findByMeetupId(meetup.getId());
        Optional<MeetupCheckin> mine = arrivals.stream().filter(c -> c.getUserId().equals(me)).findFirst();
        boolean theyArrived = arrivals.stream().anyMatch(c -> !c.getUserId().equals(me) && c.hasArrived());
        return new MeetupView(meetup.getId(), new VenueRef(venue.getId(), meetup.getVenueName(), venue.getAddress(),
                venue.getCity(), venue.getCategory(), venue.getLatitude(), venue.getLongitude(),
                venue.getZone().getId()),
                meetup.getScheduledAt(), meetup.getStatus(), meetup.getProposedByUserId().equals(me),
                meetup.plusOneOf(me), meetup.plusOneOf(meetup.otherThan(me)),
                meetup.getScheduledAt().minus(properties.checkinOpensBefore()),
                meetup.getScheduledAt().plus(properties.checkinClosesAfter()),
                mine.map(MeetupCheckin::hasArrived).orElse(false), theyArrived,
                mine.map(c -> c.getSafeConfirmedAt() != null).orElse(false),
                meetup.getCancellationType() == MeetupCancellation.CONVENIENCE ? MeetupCancellation.CONVENIENCE
                        : null);
    }

    private void requireAllowedStart(Venue venue, Instant start, Instant now, LocalDate tripStart) {
        ZoneId zone = venue.getZone();
        if (!daylight.isDaylight(start, venue.getLatitude(), venue.getLongitude(), zone)) {
            throw new ApiProblemException(MeetupProblem.NOT_IN_DAYLIGHT);
        }
        if (!daylight.allows(start, venue.getLatitude(), venue.getLongitude(), zone, now, tripStart)) {
            throw new ApiProblemException(MeetupProblem.TIME_OUT_OF_RANGE);
        }
    }

    private Match requireMatch(SessionPrincipal caller, UUID matchId) {
        return memberships.forParticipant(matchId, caller.userId())
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
    }

    private static void requireAccepted(Match match) {
        if (!match.isAccepted()) {
            throw new ApiProblemException(MeetupProblem.MATCH_CLOSED);
        }
    }

    private Meetup activeMeetup(Match match) {
        return meetups.findFirstByMemberIdOrderByCreatedAtDesc(match.memberId())
                .filter(meetup -> meetup.getStatus().isActive())
                .orElseThrow(() -> new ApiProblemException(MeetupProblem.NO_MEETUP));
    }

    /**
     * The match, read under its row lock: authorises the caller (404 for
     * anyone else) and serialises every change to one match, with the state
     * as the previous change left it.
     */
    private Match lockMatch(SessionPrincipal caller, UUID matchId) {
        return memberships.lockForParticipant(matchId, caller.userId())
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
    }

    /** The match's video-call row, created on first use without failing if another request made it first. */
    private VideoCallConfirmation confirmationRow(UUID memberId, Instant now) {
        videoCalls.createIfAbsent(memberId, now);
        return videoCalls.findForUpdate(memberId).orElseThrow();
    }

    /** A selfie stored for a check-in that then rolls back is deleted, so nothing is kept without its row. */
    private void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    storage.delete(key);
                }
            }
        });
    }

    private void consume(String bucket, SessionPrincipal caller, com.tripmate.platform.ratelimit.RateLimit limit) {
        rateLimiter.consume(bucket, caller.userId().toString(), limit);
    }

    @FunctionalInterface
    private interface Change {
        void apply(Match match, Instant now);
    }
}
