package com.tripmate.safety.dto;

/**
 * Where the phone says it is, all optional: a check-in or SOS without a
 * location still counts. Latitude and longitude come together or not at all.
 */
public record LocationRequest(Double latitude, Double longitude, Double accuracyMeters) {
}
