package com.tripmate.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The other person's real contact details, once the trip has locked. */
public record ContactResponse(

        String fullName,

        String email,

        @Schema(description = "E.164; null when they have not verified a phone number")
        String phone) {
}
