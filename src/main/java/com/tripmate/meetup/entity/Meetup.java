package com.tripmate.meetup.entity;

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
import java.util.Objects;
import java.util.UUID;

/**
 * One public meet-up between a host and an accepted companion (DESIGN.md §5
 * meetups). Every state change goes through a method here that refuses the
 * transitions the state machine does not have, and {@code version} makes two
 * changes sent together (a confirm and a cancel) unable to both commit.
 */
@Entity
@Table(name = "meetups")
public class Meetup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "host_user_id", nullable = false, updatable = false)
    private UUID hostUserId;

    @Column(name = "requester_user_id", nullable = false, updatable = false)
    private UUID requesterUserId;

    @Column(name = "proposed_by_user_id", nullable = false, updatable = false)
    private UUID proposedByUserId;

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "venue_name", nullable = false, updatable = false)
    private String venueName;

    @Column(name = "venue_latitude", nullable = false, updatable = false)
    private double venueLatitude;

    @Column(name = "venue_longitude", nullable = false, updatable = false)
    private double venueLongitude;

    @Column(name = "scheduled_at", nullable = false, updatable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeetupStatus status;

    @Column(name = "host_plus_one", nullable = false)
    private boolean hostPlusOne;

    @Column(name = "requester_plus_one", nullable = false)
    private boolean requesterPlusOne;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_type", length = 20)
    private MeetupCancellation cancellationType;

    @Column(name = "cancelled_by_user_id")
    private UUID cancelledByUserId;

    @Column(name = "emergency_contact_notified_at")
    private Instant emergencyContactNotifiedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Meetup() {
    }

    public static Meetup propose(UUID tripId, UUID memberId, UUID hostUserId, UUID requesterUserId, UUID proposerId,
                                 Venue venue, Instant scheduledAt, boolean proposerPlusOne, Instant now) {
        Objects.requireNonNull(venue, "venue");
        if (!proposerId.equals(hostUserId) && !proposerId.equals(requesterUserId)) {
            throw new IllegalArgumentException("Only the two people in the match can propose");
        }
        Meetup meetup = new Meetup();
        meetup.tripId = tripId;
        meetup.memberId = memberId;
        meetup.hostUserId = hostUserId;
        meetup.requesterUserId = requesterUserId;
        meetup.proposedByUserId = proposerId;
        meetup.venueId = venue.getId();
        meetup.venueName = venue.getName();
        meetup.venueLatitude = venue.getLatitude();
        meetup.venueLongitude = venue.getLongitude();
        meetup.scheduledAt = scheduledAt;
        meetup.status = MeetupStatus.PROPOSED;
        meetup.setPlusOne(proposerId, proposerPlusOne);
        meetup.createdAt = now;
        meetup.updatedAt = now;
        return meetup;
    }

    /** The other person agrees; the proposer cannot confirm their own proposal. */
    public void confirm(UUID userId, boolean plusOne, Instant now) {
        require(MeetupStatus.PROPOSED);
        if (proposedByUserId.equals(userId)) {
            throw new IllegalStateException("The proposer cannot confirm");
        }
        setPlusOne(userId, plusOne);
        status = MeetupStatus.CONFIRMED;
        updatedAt = now;
    }

    public void cancel(UUID userId, MeetupCancellation type, Instant now) {
        if (!status.isActive()) {
            throw new IllegalStateException("Only a meet-up being arranged can be cancelled");
        }
        status = MeetupStatus.CANCELLED;
        cancellationType = type;
        cancelledByUserId = userId;
        updatedAt = now;
    }

    public void complete(Instant now) {
        require(MeetupStatus.CONFIRMED);
        status = MeetupStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    public void markNoShow(Instant now) {
        require(MeetupStatus.CONFIRMED);
        status = MeetupStatus.NO_SHOW;
        updatedAt = now;
    }

    public void recordEmergencyContactNotified(Instant now) {
        if (emergencyContactNotifiedAt == null) {
            emergencyContactNotifiedAt = now;
        }
        updatedAt = now;
    }

    public boolean includes(UUID userId) {
        return hostUserId.equals(userId) || requesterUserId.equals(userId);
    }

    public UUID otherThan(UUID userId) {
        return hostUserId.equals(userId) ? requesterUserId : hostUserId;
    }

    public boolean plusOneOf(UUID userId) {
        return hostUserId.equals(userId) ? hostPlusOne : requesterPlusOne;
    }

    private void setPlusOne(UUID userId, boolean plusOne) {
        if (hostUserId.equals(userId)) {
            hostPlusOne = plusOne;
        } else {
            requesterPlusOne = plusOne;
        }
    }

    private void require(MeetupStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public UUID getHostUserId() {
        return hostUserId;
    }

    public UUID getRequesterUserId() {
        return requesterUserId;
    }

    public UUID getProposedByUserId() {
        return proposedByUserId;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public String getVenueName() {
        return venueName;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public MeetupStatus getStatus() {
        return status;
    }

    public MeetupCancellation getCancellationType() {
        return cancellationType;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
