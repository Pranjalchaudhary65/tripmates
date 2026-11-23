package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PhoneCodeRequest(

        @Schema(example = "+91 98765 43210",
                description = "A mobile number with its country code; a 10-digit number is read as Indian")
        @NotBlank @Size(max = 32)
        String phoneNumber) {

    /** The number is personal data; it stays out of any log line that prints a request. */
    @Override
    public String toString() {
        return "PhoneCodeRequest[]";
    }
}
