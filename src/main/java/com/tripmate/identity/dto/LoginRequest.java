package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Shape and length only; none of these rules depends on what is stored, so a
 * 400 here says nothing about any account. No minimum password length: that
 * rule belongs to choosing a password, and enforcing it here would only tell
 * a caller which guesses are worth trying.
 */
public record LoginRequest(

        @Schema(example = "asha.verma@example.com")
        @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(example = "violet-kettle-harbour-91", maxLength = 128)
        @NotEmpty @Size(max = 128)
        String password,

        @Schema(description = "The CAPTCHA widget's response token. Needed only after a 401 with "
                + "code captcha_required; omit it otherwise.", nullable = true)
        @Size(max = 4096)
        String captchaToken) {

    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
