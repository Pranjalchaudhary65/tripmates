package com.tripmate.chat.repository;

import com.tripmate.chat.entity.Conversation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Row-locked: every change to a conversation, a new message number above all, is made under this lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.tripId = :tripId and c.requesterUserId = :requesterUserId")
    Optional<Conversation> findByTripAndRequesterForUpdate(@Param("tripId") UUID tripId,
                                                           @Param("requesterUserId") UUID requesterUserId);

    boolean existsByTripIdAndRequesterUserId(UUID tripId, UUID requesterUserId);
}
