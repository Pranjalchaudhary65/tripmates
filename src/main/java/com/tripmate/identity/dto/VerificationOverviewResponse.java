package com.tripmate.identity.dto;

import com.tripmate.identity.entity.VerificationRejection;
import com.tripmate.identity.entity.VerificationStatus;
import com.tripmate.identity.entity.VerificationTier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Everything the verification centre shows, in one call. */
public record VerificationOverviewResponse(

        @Schema(description = "The highest tier reached: NONE, EMAIL, PHONE or SELFIE_MATCH")
        VerificationTier tier,

        boolean emailVerified,

        Phone phone,

        @Schema(description = "Null until a profile photo is uploaded")
        MediaLinkResponse profilePhoto,

        Selfie selfie,

        Vouches vouches) {

    public record Phone(

            boolean verified,

            @Schema(description = "The confirmed or pending number, masked; null when none was given",
                    example = "+91 ••••••• 210")
            String maskedNumber) {
    }

    public record Selfie(

            @Schema(description = "Null when there is no attempt that counts. PENDING: waiting for a reviewer")
            VerificationStatus status,

            @Schema(description = "Set only when status is REJECTED")
            VerificationRejection rejectionReason,

            Instant submittedAt) {
    }

    public record Vouches(

            @Schema(description = "The caller has reached SELFIE_MATCH and may vouch for others")
            boolean canVouch,

            long activeGiven,

            int limit,

            @Schema(description = "How many people actively vouch for the caller")
            long received) {
    }
}
