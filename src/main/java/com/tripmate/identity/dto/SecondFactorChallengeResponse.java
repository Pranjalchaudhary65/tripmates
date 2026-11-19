package com.tripmate.identity.dto;

import com.tripmate.identity.service.SecondFactorChallenge;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What the client needs to ask for the second factor. Holds no session and no account details. */
public record SecondFactorChallengeResponse(

        @Schema(description = "Send back with the code to /auth/login/second-factor. Keep it in memory only.")
        String challengeId,

        @Schema(description = "Seconds before the password has to be entered again", example = "300")
        long expiresIn,

        @Schema(description = "What the user may enter")
        List<SecondFactorMethod> methods) {

    public static SecondFactorChallengeResponse of(SecondFactorChallenge challenge) {
        return new SecondFactorChallengeResponse(challenge.id(), challenge.expiresIn().toSeconds(),
                List.of(SecondFactorMethod.TOTP, SecondFactorMethod.BACKUP_CODE));
    }

    @Override
    public String toString() {
        return "SecondFactorChallengeResponse[expiresIn=" + expiresIn + "]";
    }

    public enum SecondFactorMethod {
        TOTP, BACKUP_CODE
    }
}
