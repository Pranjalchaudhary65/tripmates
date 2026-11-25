package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** @param upiId null when none is set */
public record UpiIdResponse(UpiId upiId) {

    public record UpiId(@Schema(example = "asha.rao@okbank") String upiId, Instant updatedAt) {
    }
}
