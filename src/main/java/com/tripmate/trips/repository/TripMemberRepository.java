package com.tripmate.trips.repository;

import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.TripMember;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Join requests and memberships by ID, for writes. Lists for screens are read through {@link TripQueries}. */
public interface TripMemberRepository extends Repository<TripMember, UUID> {

    TripMember save(TripMember member);

    Optional<TripMember> findById(UUID id);

    Optional<TripMember> findByTripIdAndUserId(UUID tripId, UUID userId);

    /**
     * Served by idx_trip_members_user_status. Callers sending a request hold
     * the requester's user row lock, so parallel requests cannot pass the cap.
     */
    @Query("select count(m) from TripMember m where m.userId = :user and m.status = :status")
    long countByUserAndStatus(@Param("user") UUID userId, @Param("status") MemberStatus status);

    @Query("select count(m) from TripMember m where m.tripId = :trip and m.status = :status")
    long countByTripAndStatus(@Param("trip") UUID tripId, @Param("status") MemberStatus status);
}
