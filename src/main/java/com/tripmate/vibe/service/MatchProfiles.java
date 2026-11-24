package com.tripmate.vibe.service;

import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.VibeProfile;
import com.tripmate.vibe.repository.VibeProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The vibe dimensions matching compares, for the trips module. This is a
 * module-to-module read, never reachable from an endpoint: no route returns
 * another user's profile, and matching passes on only its explanation.
 * Raw quiz answers are not part of it.
 */
@Service
public class MatchProfiles {

    private final VibeProfileRepository profiles;

    public MatchProfiles(VibeProfileRepository profiles) {
        this.profiles = profiles;
    }

    /** Each user's dimensions, keyed by user ID; a user who has not taken the quiz is absent. */
    @Transactional(readOnly = true)
    public Map<UUID, MatchProfile> of(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return profiles.findByUserIdIn(Set.copyOf(userIds)).stream()
                .map(MatchProfiles::toMatchProfile)
                .collect(Collectors.toUnmodifiableMap(MatchProfile::userId, Function.identity()));
    }

    private static MatchProfile toMatchProfile(VibeProfile profile) {
        return new MatchProfile(profile.getUserId(), profile.dimensions(),
                profile.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * @param updatedAt when the profile last changed; a cached score computed
     *                  from an older version of it is stale. Truncated to the
     *                  microseconds the database keeps, so a value read back
     *                  from a cache row compares equal
     */
    public record MatchProfile(UUID userId, VibeDimensions dimensions, Instant updatedAt) {
    }
}
