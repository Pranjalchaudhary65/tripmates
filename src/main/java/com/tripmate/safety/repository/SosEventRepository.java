package com.tripmate.safety.repository;

import com.tripmate.safety.entity.SosEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SosEventRepository extends JpaRepository<SosEvent, UUID> {

    Optional<SosEvent> findByUserIdAndClientSosId(UUID userId, UUID clientSosId);

    /** The caller's latest SOS on the trip that is not resolved; served by idx_sos_trip_user. */
    Optional<SosEvent> findFirstByTripIdAndUserIdAndStatusNotOrderByTriggeredAtDesc(UUID tripId, UUID userId,
                                                                                   SosEvent.Status status);

    /**
     * SOS events whose alert has not been handed over yet and still has tries
     * left, oldest first; served by idx_sos_open. One out of tries stays open
     * for the moderator its flag already reached.
     */
    @Query(value = """
            SELECT id FROM sos_events WHERE status = 'OPEN' AND alert_attempts < :maxAttempts
            ORDER BY triggered_at LIMIT :batch""", nativeQuery = true)
    List<UUID> findUnalerted(@Param("maxAttempts") int maxAttempts, @Param("batch") int batch);

    @Query(value = "SELECT * FROM sos_events WHERE id = :id AND status = 'OPEN' FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<SosEvent> lockIfOpen(@Param("id") UUID id);
}
