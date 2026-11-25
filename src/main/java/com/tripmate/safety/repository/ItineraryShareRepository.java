package com.tripmate.safety.repository;

import com.tripmate.safety.entity.ItineraryShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItineraryShareRepository extends JpaRepository<ItineraryShare, UUID> {

    Optional<ItineraryShare> findByTripIdAndUserId(UUID tripId, UUID userId);

    List<ItineraryShare> findByTripId(UUID tripId);
}
