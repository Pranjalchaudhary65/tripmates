package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The UPI ID to be paid at. Its format is checked, and it is lower-cased, by the server. */
public record UpiIdRequest(

        @NotBlank
        @Size(max = 100)
        @Schema(example = "asha.rao@okbank")
        String upiId) {
}
