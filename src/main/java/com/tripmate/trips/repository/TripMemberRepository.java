package com.tripmate.trips.repository;

import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.TripMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Join requests and memberships by ID, for writes. Lists for screens are read through {@link TripQueries}. */
public interface TripMemberRepository extends Repository<TripMember, UUID> {

    TripMember save(TripMember member);

    Optional<TripMember> findById(UUID id);

    Optional<TripMember> findByTripIdAndUserId(UUID tripId, UUID userId);

    /** Row-locked, for changes along the trust ladder after acceptance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TripMember m where m.id = :id")
    Optional<TripMember> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Served by idx_trip_members_user_status. Callers sending a request hold
     * the requester's user row lock, so parallel requests cannot pass the cap.
     */
    @Query("select count(m) from TripMember m where m.userId = :user and m.status = :status")
    long countByUserAndStatus(@Param("user") UUID userId, @Param("status") MemberStatus status);

    @Query("select count(m) from TripMember m where m.tripId = :trip and m.status = :status")
    long countByTripAndStatus(@Param("trip") UUID tripId, @Param("status") MemberStatus status);

    /** Every companion who was ever locked in on the trip, in the order they were; served by idx_trip_members_trip_status. */
    @Query("select m from TripMember m where m.tripId = :trip and m.lockedAt is not null order by m.lockedAt, m.id")
    List<TripMember> findEverLockedByTrip(@Param("trip") UUID tripId);
}
