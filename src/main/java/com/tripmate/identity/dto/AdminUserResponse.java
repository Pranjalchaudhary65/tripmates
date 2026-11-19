package com.tripmate.identity.dto;

import com.tripmate.identity.entity.AccountStatus;
import com.tripmate.identity.entity.Role;
import com.tripmate.identity.entity.VerificationTier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * An account as a moderator sees it: status and verification, never the
 * password hash, the TOTP secret, or anything encrypted.
 */
public record AdminUserResponse(

        UUID id,

        @Schema(example = "asha.verma@example.com")
        String email,

        @Schema(example = "Asha Verma")
        String fullName,

        Role role,

        AccountStatus accountStatus,

        VerificationTier verificationTier,

        boolean twoFactorEnabled,

        @Schema(nullable = true)
        Instant emailVerifiedAt,

        Instant createdAt,

        @Schema(nullable = true)
        Instant deletedAt) {
}
