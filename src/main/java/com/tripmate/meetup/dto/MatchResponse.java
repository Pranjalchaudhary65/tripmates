package com.tripmate.meetup.dto;

import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.meetup.entity.MeetupCancellation;
import com.tripmate.meetup.entity.MeetupStatus;
import com.tripmate.meetup.entity.VenueCategory;
import com.tripmate.meetup.service.MeetupViews.Role;
import com.tripmate.meetup.service.MeetupViews.Stage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The match screen in one call: where the trust ladder stands and what the
 * caller can do next. Never carries the other person's ID, their location or
 * anyone's comfort rating. Once a match did not proceed, only the stage, the
 * trip and the other person's first name remain.
 */
public record MatchResponse(

        UUID matchId,

        Role myRole,

        Trip trip,

        Person otherPerson,

        @Schema(description = "DID_NOT_PROCEED never says why or who decided")
        Stage stage,

        @Schema(description = "Null once the match did not proceed")
        VideoCall videoCall,

        boolean emergencyContactSet,

        @Schema(description = "The latest meet-up; null when none, and once the match did not proceed")
        Meetup meetup,

        boolean myFeedbackGiven) {

    public record Trip(UUID id, String title, String destinationName, LocalDate startDate) {
    }

    public record Person(String firstName, VerificationTier verificationTier) {
    }

    public record VideoCall(boolean confirmedByMe, boolean confirmedByThem, boolean verified) {
    }

    public record Meetup(UUID id, Venue venue, Instant scheduledAt, MeetupStatus status, boolean proposedByMe,
                         boolean myPlusOne, boolean theirPlusOne, Instant checkInOpensAt, Instant checkInClosesAt,
                         boolean iArrived,
                         @Schema(description = "Whether the other person has checked in; never where they are")
                         boolean theyArrived,
                         boolean iConfirmedSafe,
                         @Schema(description = "Set only for a change-of-plan cancellation")
                         MeetupCancellation cancellation) {
    }

    public record Venue(UUID id, String name, String address, String city, VenueCategory category, double latitude,
                        double longitude, @Schema(example = "Asia/Kolkata") String timeZone) {
    }
}
