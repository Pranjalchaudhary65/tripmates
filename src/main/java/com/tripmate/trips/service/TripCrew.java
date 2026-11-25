package com.tripmate.trips.service;

import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.Trip;
import com.tripmate.trips.entity.TripMember;
import com.tripmate.trips.entity.TripStatus;
import com.tripmate.trips.repository.TripMemberRepository;
import com.tripmate.trips.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is travelling on a trip once it has locked: the host and every
 * companion locked in through a public meet-up. No endpoint reaches this;
 * the modules that work on a locked trip (the shared ledger, live-trip
 * safety) read it to decide who may act.
 *
 * <p>The lock itself is the meet-up's gate, recorded through
 * {@link TripMembership#lock}; nothing here can lock anyone.
 */
@Service
public class TripCrew {

    private final TripRepository trips;

    private final TripMemberRepository members;

    public TripCrew(TripRepository trips, TripMemberRepository members) {
        this.trips = trips;
        this.members = members;
    }

    /** Empty when there is no such trip. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Crew> of(UUID tripId) {
        return trips.findById(tripId).map(trip -> {
            List<Companion> companions = new ArrayList<>();
            for (TripMember member : members.findEverLockedByTrip(tripId)) {
                companions.add(new Companion(member.getId(), member.getUserId(), member.getLockedAt(),
                        member.getStatus() == MemberStatus.LOCKED || member.getStatus() == MemberStatus.COMPLETED));
            }
            return new Crew(trip, companions);
        });
    }

    /**
     * @param companions every companion ever locked in, in the order they
     *                   were; {@code onTrip} is false for one who has since
     *                   stepped back
     */
    public record Crew(UUID tripId, String title, String destinationName, double destinationLatitude,
                       double destinationLongitude, LocalDate startDate, LocalDate endDate, boolean cancelled,
                       UUID hostUserId, List<Companion> companions) {

        Crew(Trip trip, List<Companion> companions) {
            this(trip.getId(), trip.getTitle(), trip.getDestinationName(), trip.getDestinationLatitude(),
                    trip.getDestinationLongitude(), trip.getStartDate(), trip.getEndDate(),
                    trip.getStatus() == TripStatus.CANCELLED, trip.getHostUserId(), List.copyOf(companions));
        }

        /** The host and the companions still on the trip, host first. */
        public List<UUID> travellers() {
            List<UUID> travellers = new ArrayList<>();
            travellers.add(hostUserId);
            companions.stream().filter(Companion::onTrip).map(Companion::userId).forEach(travellers::add);
            return List.copyOf(travellers);
        }

        /** Locked: the host has at least one companion still on the trip. */
        public boolean isLocked() {
            return !cancelled && companions.stream().anyMatch(Companion::onTrip);
        }

        public boolean isTraveller(UUID userId) {
            return travellers().contains(userId);
        }

        /** A traveller now, or a companion who was locked in and stepped back later. */
        public boolean everTravelled(UUID userId) {
            return hostUserId.equals(userId) || companions.stream().anyMatch(c -> c.userId().equals(userId));
        }

        public boolean isHost(UUID userId) {
            return hostUserId.equals(userId);
        }
    }

    public record Companion(UUID memberId, UUID userId, Instant lockedAt, boolean onTrip) {
    }
}
