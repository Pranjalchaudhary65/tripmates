package com.tripmate.trips.repository;

import com.tripmate.trips.entity.Trip;
import com.tripmate.trips.entity.TripStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Trips by ID, for writes. Lists for screens are read through {@link TripQueries}. */
public interface TripRepository extends Repository<Trip, UUID> {

    Trip save(Trip trip);

    Optional<Trip> findById(UUID id);

    /**
     * Trips a host still has to run: listed and not over yet. Served by
     * idx_trips_host_status. Callers creating a trip hold the host's user
     * row lock, which is what makes "count, then insert" safe against the cap.
     */
    @Query("""
            select count(t) from Trip t
            where t.hostUserId = :host and t.status in :statuses and t.endDate >= :today""")
    long countCurrentHostedBy(@Param("host") UUID hostUserId, @Param("statuses") Collection<TripStatus> statuses,
                              @Param("today") LocalDate today);
}
