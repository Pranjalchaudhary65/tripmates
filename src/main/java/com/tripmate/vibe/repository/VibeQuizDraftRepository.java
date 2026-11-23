package com.tripmate.vibe.repository;

import com.tripmate.vibe.entity.VibeQuizDraft;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** One row per user, keyed by the user ID. Callers hold the user row lock (AccountService#lockActive) when writing. */
public interface VibeQuizDraftRepository extends Repository<VibeQuizDraft, UUID> {

    VibeQuizDraft save(VibeQuizDraft row);

    Optional<VibeQuizDraft> findById(UUID userId);

    void deleteById(UUID userId);
}
