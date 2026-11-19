package com.tripmate.identity.dto;

import com.tripmate.identity.service.Totp;
import com.tripmate.identity.service.TwoFactorService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The new secret, returned exactly once. It is stored only encrypted, and no
 * endpoint ever returns it again: a client that loses this response starts
 * setup over.
 */
public record TwoFactorSetupResponse(

        @Schema(description = "Base32, no padding: type it into an authenticator app by hand, or use otpauthUri",
                example = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP")
        String secret,

        @Schema(description = "Render as a QR code for the authenticator app to scan")
        String otpauthUri,

        @Schema(example = "TripMates")
        String issuer,

        @Schema(description = "The account name the app shows", example = "asha.verma@example.com")
        String accountName,

        @Schema(example = "SHA1")
        String algorithm,

        @Schema(example = "6")
        int digits,

        @Schema(description = "Seconds each code is valid for", example = "30")
        int period) {

    public static TwoFactorSetupResponse of(TwoFactorService.TwoFactorSetup setup) {
        return new TwoFactorSetupResponse(setup.secret(), setup.otpauthUri(), setup.issuer(), setup.accountName(),
                Totp.ALGORITHM, Totp.DIGITS, Totp.PERIOD_SECONDS);
    }

    @Override
    public String toString() {
        return "TwoFactorSetupResponse[issuer=" + issuer + "]";
    }
}
