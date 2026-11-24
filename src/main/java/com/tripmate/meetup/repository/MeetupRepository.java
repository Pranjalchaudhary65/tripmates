package com.tripmate.meetup.repository;

import com.tripmate.meetup.entity.Meetup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetupRepository extends JpaRepository<Meetup, UUID> {

    /** The match's most recent meet-up, whatever became of it. */
    Optional<Meetup> findFirstByMemberIdOrderByCreatedAtDesc(UUID memberId);

    /** Confirmed meet-ups that started before the instant, oldest first; served by idx_meetups_confirmed_schedule. */
    @Query(value = """
            SELECT id FROM meetups
            WHERE status = 'CONFIRMED' AND scheduled_at <= :startedBefore
            ORDER BY scheduled_at
            LIMIT :batch""", nativeQuery = true)
    List<UUID> findConfirmedStartedBefore(@Param("startedBefore") Instant startedBefore, @Param("batch") int batch);

    /**
     * The meet-up, still confirmed, locked for this caller; empty when another
     * app instance running the same job holds it, so each is handled once.
     */
    @Query(value = "SELECT * FROM meetups WHERE id = :id AND status = 'CONFIRMED' FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Meetup> lockIfConfirmed(@Param("id") UUID id);
}
