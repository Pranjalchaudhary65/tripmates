package com.tripmate.meetup.service;

import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.meetup.entity.MeetupCancellation;
import com.tripmate.meetup.entity.MeetupStatus;
import com.tripmate.meetup.entity.VenueCategory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the meet-up service hands to the controller. Nothing here can carry
 * the other person's user ID, their arrival point, or anyone's comfort rating.
 */
public final class MeetupViews {

    private MeetupViews() {
    }

    /** Where a match stands on the ladder, from the caller's side. */
    public enum Stage {
        /** Both people still need to confirm their video call. */
        VIDEO_CALL,
        /** Video call done; nobody has proposed a meet-up (or the last one fell through). */
        ARRANGE_MEETUP,
        /** Proposed and waiting for the other person. */
        AWAITING_CONFIRMATION,
        CONFIRMED,
        /** Both checked in; the caller has not said how it felt yet. */
        FEEDBACK,
        /** The caller has; the other person has not. */
        WAITING_FOR_OTHER,
        /** The trip is locked for this companion. */
        LOCKED,
        /** The match stopped. Never says why or by whom. */
        DID_NOT_PROCEED,
        /** The membership ended some other way (the trip is over, say). */
        CLOSED
    }

    public enum Role {
        HOST, COMPANION
    }

    /**
     * @param videoCall  null once the match did not proceed
     * @param meetup     the latest meet-up; null when none, and always null once the match did not proceed
     */
    public record MatchView(UUID matchId, Role myRole, TripRef trip, Person otherPerson, Stage stage,
                            VideoCall videoCall, boolean emergencyContactSet, MeetupView meetup,
                            boolean myFeedbackGiven) {
    }

    public record TripRef(UUID id, String title, String destinationName, LocalDate startDate) {
    }

    public record Person(String firstName, VerificationTier verificationTier) {
    }

    public record VideoCall(boolean confirmedByMe, boolean confirmedByThem, boolean verified) {
    }

    /**
     * @param theyArrived only whether, never where
     * @param cancellation set for a change-of-plan cancellation only
     */
    public record MeetupView(UUID id, VenueRef venue, Instant scheduledAt, MeetupStatus status, boolean proposedByMe,
                             boolean myPlusOne, boolean theirPlusOne, Instant checkInOpensAt, Instant checkInClosesAt,
                             boolean iArrived, boolean theyArrived, boolean iConfirmedSafe,
                             MeetupCancellation cancellation) {
    }

    /** @param timeZone the venue's zone, in which its times are shown */
    public record VenueRef(UUID id, String name, String address, String city, VenueCategory category,
                           double latitude, double longitude, String timeZone) {
    }

    /** A venue the caller may propose, with the start times it allows. */
    public record VenueOption(VenueRef venue, double distanceKm, String timeZone, List<DaylightRules.Window> windows) {
    }

    /** What the caller's own check-in did. */
    public record CheckInResult(boolean arrived, boolean meetupCompleted) {
    }
}
