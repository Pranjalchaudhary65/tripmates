package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Wrapped in an object, not a bare array, so fields can be added later without breaking clients. */
public record ActiveSessionListResponse(

        @Schema(description = "Live sessions, most recently active first; at most 100")
        List<ActiveSessionResponse> items) {
}
