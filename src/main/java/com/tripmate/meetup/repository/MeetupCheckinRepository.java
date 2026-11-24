package com.tripmate.meetup.repository;

import com.tripmate.meetup.entity.MeetupCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetupCheckinRepository extends JpaRepository<MeetupCheckin, UUID> {

    Optional<MeetupCheckin> findByMeetupIdAndUserId(UUID meetupId, UUID userId);

    List<MeetupCheckin> findByMeetupId(UUID meetupId);

    /** Stored selfies past their retention, locked and skipped by other instances like the safety job. */
    @Query(value = """
            SELECT * FROM meetup_checkins
            WHERE selfie_key IS NOT NULL AND selfie_purge_due_at <= :now
            ORDER BY selfie_purge_due_at
            LIMIT :batch
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<MeetupCheckin> lockSelfiesDueBy(@Param("now") Instant now, @Param("batch") int batch);
}
