package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The caller's vouches, given and received, for the vouch screen. */
public record VouchSummaryResponse(

        @Schema(description = "The caller has reached SELFIE_MATCH and may vouch")
        boolean canVouch,

        @Schema(description = "How many people one account can vouch for at once", example = "5")
        int limit,

        List<Given> given,

        @Schema(description = "How many people actively vouch for the caller")
        long receivedCount) {

    public record Given(

            @Schema(description = "Withdraw with DELETE /vouches/{id}")
            UUID id,

            UUID vouchedUserId,

            @Schema(example = "Asha")
            String vouchedFirstName,

            Instant since) {
    }
}
