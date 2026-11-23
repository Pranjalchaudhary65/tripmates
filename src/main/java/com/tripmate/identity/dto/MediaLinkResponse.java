package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** A signed link to one image. Anyone holding it can load the image until it expires, so it is never stored. */
public record MediaLinkResponse(

        @Schema(description = "Relative to the API base (/api/v1); use as an image source", example = "/media/eyJ...")
        String path,

        @Schema(description = "After this the link answers 404; fetch the screen again for a fresh one")
        Instant expiresAt) {
}
