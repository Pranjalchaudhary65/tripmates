package com.tripmate.safety.repository;

import com.tripmate.safety.entity.Checkin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckinRepository extends JpaRepository<Checkin, UUID> {

    /** The one check-in in that status (for PENDING there is at most one, uq_checkins_one_pending). */
    Optional<Checkin> findFirstByTripIdAndUserIdAndStatus(UUID tripId, UUID userId, Checkin.Status status);

    /** The latest answered check-in. */
    Optional<Checkin> findFirstByTripIdAndUserIdAndStatusOrderByRespondedAtDesc(UUID tripId, UUID userId,
                                                                               Checkin.Status status);

    /** Pending check-ins due before the instant, oldest first; served by idx_checkins_pending_due. */
    @Query(value = """
            SELECT id FROM checkins WHERE status = 'PENDING' AND scheduled_at <= :dueBefore
            ORDER BY scheduled_at LIMIT :batch""", nativeQuery = true)
    List<UUID> findPendingDueBefore(@Param("dueBefore") Instant dueBefore, @Param("batch") int batch);

    /** Still pending and locked for this caller; empty when another instance holds it. */
    @Query(value = "SELECT * FROM checkins WHERE id = :id AND status = 'PENDING' FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Checkin> lockIfPending(@Param("id") UUID id);
}
