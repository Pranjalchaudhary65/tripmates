package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** No free-text reason: what a user is told comes from a fixed list, never from what a reviewer typed. */
public record ReviewDecisionRequest(

        @Schema(description = "APPROVE raises the account to SELFIE_MATCH; REJECT records REVIEWER_REJECTED")
        @NotNull
        Decision decision) {

    public enum Decision {
        APPROVE, REJECT
    }
}
