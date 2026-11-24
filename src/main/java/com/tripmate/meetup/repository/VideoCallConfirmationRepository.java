package com.tripmate.meetup.repository;

import com.tripmate.meetup.entity.VideoCallConfirmation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** One row per match, created the first time either person confirms the call. */
public interface VideoCallConfirmationRepository extends JpaRepository<VideoCallConfirmation, UUID> {

    /** Makes sure the row exists without failing when another request created it first. */
    @Modifying
    @Query(value = """
            INSERT INTO video_call_confirmations (member_id, created_at, updated_at)
            VALUES (:memberId, :now, :now)
            ON CONFLICT (member_id) DO NOTHING""", nativeQuery = true)
    void createIfAbsent(@Param("memberId") UUID memberId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VideoCallConfirmation v where v.memberId = :memberId")
    Optional<VideoCallConfirmation> findForUpdate(@Param("memberId") UUID memberId);
}
