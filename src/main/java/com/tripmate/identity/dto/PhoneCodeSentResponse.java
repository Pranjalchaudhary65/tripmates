package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record PhoneCodeSentResponse(

        @Schema(description = "Send this back with the code")
        UUID verificationId,

        @Schema(example = "+91 ••••••• 210")
        String maskedNumber,

        @Schema(description = "Seconds the code stays valid", example = "600")
        long expiresIn,

        @Schema(description = "Seconds before another code can be requested", example = "60")
        long resendAfter) {
}
