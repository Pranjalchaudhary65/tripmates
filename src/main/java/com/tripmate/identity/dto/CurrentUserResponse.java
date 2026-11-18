package com.tripmate.identity.dto;

import com.tripmate.identity.entity.Role;
import com.tripmate.identity.entity.VerificationTier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** The signed-in user's own account, as read from the database on this request. */
public record CurrentUserResponse(

        UUID id,

        @Schema(example = "asha.verma@example.com")
        String email,

        @Schema(example = "Asha Verma")
        String fullName,

        Role role,

        VerificationTier verificationTier) {
}
