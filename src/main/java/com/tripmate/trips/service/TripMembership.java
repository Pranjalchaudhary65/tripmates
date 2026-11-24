package com.tripmate.trips.service;

import com.tripmate.trips.entity.CancellationType;
import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.Trip;
import com.tripmate.trips.entity.TripMember;
import com.tripmate.trips.repository.TripMemberRepository;
import com.tripmate.trips.repository.TripRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The trust ladder after acceptance, as other modules drive it. No endpoint
 * reaches this; the meet-up module calls it once it has decided, under its
 * own rules, that the video call happened, that the meet-up went well, or
 * that someone stepped back.
 *
 * <p>A "match" here is one accepted join request: its ID is the
 * {@code trip_members} row's, which both the host and the companion already
 * hold, and which means nothing to anyone else.
 */
@Service
public class TripMembership {

    private final TripMemberRepository members;

    private final TripRepository trips;

    private final ApplicationEventPublisher events;

    public TripMembership(TripMemberRepository members, TripRepository trips, ApplicationEventPublisher events) {
        this.members = members;
        this.trips = trips;
        this.events = events;
    }

    /**
     * The match, only if {@code userId} is its host or its companion and it
     * got as far as acceptance; empty for anyone else, so the caller answers
     * 404 the same way for "not yours" and "does not exist".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Match> forParticipant(UUID memberId, UUID userId) {
        return members.findById(memberId)
                .filter(member -> member.getStatus() != MemberStatus.REQUESTED
                        && member.getStatus() != MemberStatus.REJECTED)
                .flatMap(member -> trips.findById(member.getTripId()).map(trip -> new Match(member, trip)))
                .filter(match -> match.hostUserId().equals(userId) || match.companionUserId().equals(userId));
    }

    /**
     * As {@link #forParticipant}, with the membership row locked for the rest
     * of the caller's transaction: every change another module makes to one
     * match queues behind the last, and sees the state it left.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Match> lockForParticipant(UUID memberId, UUID userId) {
        return members.findByIdForUpdate(memberId)
                .filter(member -> member.getStatus() != MemberStatus.REQUESTED
                        && member.getStatus() != MemberStatus.REJECTED)
                .flatMap(member -> trips.findById(member.getTripId()).map(trip -> new Match(member, trip)))
                .filter(match -> match.hostUserId().equals(userId) || match.companionUserId().equals(userId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmVideoCall(UUID memberId, Instant at) {
        TripMember member = members.findById(memberId).orElseThrow();
        member.confirmVideoCall(at);
        members.save(member);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID memberId, Instant at) {
        TripMember member = members.findById(memberId).orElseThrow();
        Trip trip = trips.findById(member.getTripId()).orElseThrow();
        member.lock(at);
        members.save(member);
        events.publishEvent(new CompanionLocked(trip.getId(), member.getId(), trip.getHostUserId(),
                member.getUserId(), at));
    }

    /** Frees the companion's place and ends the match, with no reason recorded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForSafety(UUID memberId, Instant at) {
        TripMember member = members.findById(memberId).orElseThrow();
        Trip trip = trips.findById(member.getTripId()).orElseThrow();
        member.releaseForSafety();
        trip.releaseSlot(at);
        members.save(member);
        trips.save(trip);
        events.publishEvent(new CompanionReleased(trip.getId(), member.getId(), trip.getHostUserId(),
                member.getUserId(), at));
    }

    /**
     * One accepted (or since locked or released) companion on a trip, with
     * the trip facts the meet-up needs.
     */
    public record Match(UUID memberId, UUID tripId, UUID hostUserId, UUID companionUserId, MemberStatus status,
                        CancellationType cancellationType, Instant videoVerifiedAt, String tripTitle,
                        String destinationName, double destinationLatitude, double destinationLongitude,
                        LocalDate tripStartDate) {

        Match(TripMember member, Trip trip) {
            this(member.getId(), trip.getId(), trip.getHostUserId(), member.getUserId(), member.getStatus(),
                    member.getCancellationType(), member.getVideoVerifiedAt(), trip.getTitle(),
                    trip.getDestinationName(), trip.getDestinationLatitude(), trip.getDestinationLongitude(),
                    trip.getStartDate());
        }

        public boolean isHost(UUID userId) {
            return hostUserId.equals(userId);
        }

        public UUID otherThan(UUID userId) {
            return isHost(userId) ? companionUserId : hostUserId;
        }

        /** Accepted and not yet locked or released: the meet-up rung is open. */
        public boolean isAccepted() {
            return status == MemberStatus.ACCEPTED;
        }

        public boolean isLocked() {
            return status == MemberStatus.LOCKED;
        }

        /** Stopped for safety, by either side; never says by whom. */
        public boolean didNotProceed() {
            return status == MemberStatus.CANCELLED && cancellationType == CancellationType.SAFETY;
        }
    }
}
