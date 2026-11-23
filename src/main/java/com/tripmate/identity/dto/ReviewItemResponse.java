package com.tripmate.identity.dto;

import com.tripmate.identity.entity.VerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One selfie in the review queue, with the photo it was compared against. */
public record ReviewItemResponse(

        UUID verificationId,

        UUID userId,

        String fullName,

        VerificationStatus status,

        @Schema(description = "0 to 1, from the liveness provider", example = "0.734")
        BigDecimal similarityScore,

        Instant submittedAt,

        @Schema(description = "The selfie is deleted at this time, reviewed or not")
        Instant purgeDueAt,

        @Schema(description = "Null once the image has been purged")
        MediaLinkResponse selfie,

        MediaLinkResponse profilePhoto) {
}
