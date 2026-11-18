package com.tripmate.identity.dto;

import com.tripmate.identity.entity.Role;
import com.tripmate.identity.entity.VerificationTier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** What a client needs to pick screens and route guards; the server re-checks all of it on every request. */
public record SessionUserResponse(

        UUID id,

        Role role,

        @Schema(description = "Highest identity verification the account has completed")
        VerificationTier verificationTier) {
}
