package com.tripmate.trips.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One person's place on a trip, from join request onwards (DESIGN.md §3
 * trip_members). The host is not a member: a row here is always someone
 * asking to join, or who has joined, another person's trip.
 *
 * <p>Maps the columns the join request flow uses; video confirmation,
 * cancellation and lock columns stay unmapped until those steps of the trust
 * ladder exist. {@code version} makes an accept and a reject of the same
 * request, sent together, unable to both commit.
 */
@Entity
@Table(name = "trip_members")
public class TripMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected TripMember() {
    }

    public static TripMember request(UUID tripId, UUID userId, Instant now) {
        TripMember member = new TripMember();
        member.tripId = tripId;
        member.userId = userId;
        member.status = MemberStatus.REQUESTED;
        member.requestedAt = now;
        return member;
    }

    public boolean isPending() {
        return status == MemberStatus.REQUESTED;
    }

    public void accept(Instant now) {
        decide(MemberStatus.ACCEPTED, now);
    }

    public void reject(Instant now) {
        decide(MemberStatus.REJECTED, now);
    }

    private void decide(MemberStatus outcome, Instant now) {
        if (!isPending()) {
            throw new IllegalStateException("Join request already decided");
        }
        status = outcome;
        decidedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getUserId() {
        return userId;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
