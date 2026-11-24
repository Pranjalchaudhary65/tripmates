package com.tripmate.meetup.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * @param venueId         one of the allowlisted venues; nothing else is accepted
 * @param scheduledAt     inside one of that venue's daylight windows
 * @param bringingPlusOne the proposer is bringing a friend along
 */
public record ProposeMeetupRequest(@NotNull UUID venueId, @NotNull Instant scheduledAt, boolean bringingPlusOne) {
}
