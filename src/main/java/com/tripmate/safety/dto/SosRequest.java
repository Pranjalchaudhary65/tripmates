package com.tripmate.safety.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param clientSosId chosen once per press and reused on every retry of it, so one press raises one SOS
 */
public record SosRequest(@NotNull UUID clientSosId, Double latitude, Double longitude, Double accuracyMeters) {
}
