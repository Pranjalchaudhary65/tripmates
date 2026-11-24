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
 * <p>The rest of the trust ladder after acceptance is the meet-up module's to
 * drive, through {@code TripMembership}: the video call confirmation, the
 * lock once a public meet-up went well, and the safety release when it did
 * not. {@code version} makes any two of those changes, sent together, unable
 * to both commit.
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

    @Column(name = "video_verified_at")
    private Instant videoVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_type", length = 20)
    private CancellationType cancellationType;

    @Column(name = "locked_at")
    private Instant lockedAt;

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

    /** Both people confirmed their in-app video call. Repeating it keeps the first time. */
    public void confirmVideoCall(Instant now) {
        requireStatus(MemberStatus.ACCEPTED);
        if (videoVerifiedAt == null) {
            videoVerifiedAt = now;
        }
    }

    /** The public meet-up went well for both: the companion is locked in. */
    public void lock(Instant now) {
        requireStatus(MemberStatus.ACCEPTED);
        if (videoVerifiedAt == null) {
            throw new IllegalStateException("Cannot lock before the video call");
        }
        status = MemberStatus.LOCKED;
        lockedAt = now;
    }

    /**
     * Someone stepped back for their safety. Free by construction: this is the
     * only path to a SAFETY cancellation, and it records nothing else.
     */
    public void releaseForSafety() {
        if (status != MemberStatus.ACCEPTED && status != MemberStatus.LOCKED) {
            throw new IllegalStateException("Only a companion with a place can be released");
        }
        status = MemberStatus.CANCELLED;
        cancellationType = CancellationType.SAFETY;
    }

    private void requireStatus(MemberStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + status);
        }
    }

    public Instant getVideoVerifiedAt() {
        return videoVerifiedAt;
    }

    public CancellationType getCancellationType() {
        return cancellationType;
    }

    public Instant getLockedAt() {
        return lockedAt;
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
