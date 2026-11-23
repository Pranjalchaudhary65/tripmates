package com.tripmate.vibe.repository;

import com.tripmate.vibe.entity.VibeProfile;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** One row per user, keyed by the user ID. Callers hold the user row lock (AccountService#lockActive) when writing. */
public interface VibeProfileRepository extends Repository<VibeProfile, UUID> {

    VibeProfile save(VibeProfile row);

    Optional<VibeProfile> findById(UUID userId);
}
