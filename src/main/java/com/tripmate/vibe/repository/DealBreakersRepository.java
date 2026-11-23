package com.tripmate.vibe.repository;

import com.tripmate.vibe.entity.DealBreakers;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** One row per user, keyed by the user ID. Callers hold the user row lock (AccountService#lockActive) when writing. */
public interface DealBreakersRepository extends Repository<DealBreakers, UUID> {

    DealBreakers save(DealBreakers row);

    Optional<DealBreakers> findById(UUID userId);
}
