package com.tripmate.identity.dto;

import com.tripmate.identity.entity.EmergencyContact.Relationship;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The caller's emergency contact. The number is normalised to E.164 by the server. */
public record EmergencyContactRequest(

        @NotBlank
        @Size(max = 80)
        @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}<>]*$", message = "must not contain control characters or angle brackets")
        @Schema(example = "Meera Rao")
        String name,

        @NotNull
        Relationship relationship,

        @NotBlank
        @Size(max = 32)
        @Schema(example = "+91 98765 43210")
        String phoneNumber) {
}
