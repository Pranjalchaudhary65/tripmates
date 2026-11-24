package com.tripmate.trips.dto;

import com.tripmate.identity.entity.VerificationTier;
import io.swagger.v3.oas.annotations.media.Schema;

/** Another traveller, as a stranger sees them: no user ID, surname, contact or photo. */
public record PersonResponse(

        @Schema(example = "Asha")
        String firstName,

        VerificationTier verificationTier) {
}
