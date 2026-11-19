package com.tripmate.identity.dto;

import com.tripmate.identity.service.TwoFactorService;
import io.swagger.v3.oas.annotations.media.Schema;

/** Everything the 2FA settings screen needs, in one call. Never includes the secret or any code. */
public record TwoFactorStatusResponse(

        @Schema(description = "Signing in needs a TOTP or backup code after the password")
        boolean enabled,

        @Schema(description = "Setup was started and is waiting for the first code from the authenticator app")
        boolean setupPending,

        @Schema(description = "Unused backup codes; 0 while 2FA is off", example = "10")
        int backupCodesRemaining) {

    public static TwoFactorStatusResponse of(TwoFactorService.TwoFactorStatus status) {
        return new TwoFactorStatusResponse(status.enabled(), status.setupPending(), status.backupCodesRemaining());
    }
}
