package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One signed-in session as the sessions screen shows it. Every text field is
 * produced by the server: {@code device} comes from a fixed vocabulary and
 * {@code ipAddress} is formatted from a parsed address, so nothing the
 * client sent at sign-in (such as its User-Agent) is ever echoed back.
 */
public record ActiveSessionResponse(

        UUID id,

        @Schema(description = "Browser and operating system, from a fixed list of names", example = "Firefox on Linux")
        String device,

        @Schema(description = "Address the most recent refresh came from", example = "203.0.113.7")
        String ipAddress,

        Instant signedInAt,

        Instant lastActiveAt,

        @Schema(description = "When this session ends regardless of activity")
        Instant expiresAt,

        @Schema(description = "True for the session the calling access token belongs to")
        boolean current) {
}
