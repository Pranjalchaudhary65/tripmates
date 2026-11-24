package com.tripmate.meetup.dto;

import com.tripmate.meetup.entity.MeetupCancellation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** No reason text, by design: stepping back for safety never has to be explained. */
public record CancelMeetupRequest(
        @NotNull
        @Schema(description = "SAFETY ends the match for both, at no cost; CONVENIENCE allows a new proposal")
        MeetupCancellation type) {
}
