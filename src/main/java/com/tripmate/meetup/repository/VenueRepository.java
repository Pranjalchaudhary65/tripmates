package com.tripmate.meetup.repository;

import com.tripmate.meetup.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VenueRepository extends JpaRepository<Venue, UUID> {

    Optional<Venue> findByIdAndActiveTrue(UUID id);
}
