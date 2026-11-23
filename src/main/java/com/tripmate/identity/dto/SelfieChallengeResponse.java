package com.tripmate.identity.dto;

import com.tripmate.identity.verification.LivenessPrompt;
import io.swagger.v3.oas.annotations.media.Schema;

/** A started selfie check. Capture both frames in the app and send them before it expires. */
public record SelfieChallengeResponse(

        @Schema(description = "Send this back with the two frames; good for one submission")
        String challengeId,

        @Schema(description = "What to do in the second frame")
        LivenessPrompt prompt,

        @Schema(description = "Seconds until the check expires", example = "120")
        long expiresIn) {

    @Override
    public String toString() {
        return "SelfieChallengeResponse[prompt=" + prompt + "]";
    }
}
