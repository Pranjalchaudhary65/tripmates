package com.tripmate.meetup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One person's arrival at a confirmed meet-up (DESIGN.md §5 meetup_checkins).
 * Created for both people when the meet-up is confirmed, so the safety job
 * can find whoever has not arrived. Arrival takes both signals at once: a
 * point inside the geofence and a selfie, written together or not at all.
 */
@Entity
@Table(name = "meetup_checkins")
public class MeetupCheckin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meetup_id", nullable = false, updatable = false)
    private UUID meetupId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "arrival_latitude")
    private Double arrivalLatitude;

    @Column(name = "arrival_longitude")
    private Double arrivalLongitude;

    @Column(name = "distance_meters", precision = 7, scale = 1)
    private BigDecimal distanceMeters;

    @Column(name = "selfie_key")
    private String selfieKey;

    @Column(name = "selfie_purge_due_at")
    private Instant selfiePurgeDueAt;

    @Column(name = "safe_confirmed_at")
    private Instant safeConfirmedAt;

    @Column(name = "pinged_at")
    private Instant pingedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MeetupCheckin() {
    }

    public static MeetupCheckin expect(UUID meetupId, UUID userId, Instant now) {
        MeetupCheckin checkin = new MeetupCheckin();
        checkin.meetupId = meetupId;
        checkin.userId = userId;
        checkin.createdAt = now;
        checkin.updatedAt = now;
        return checkin;
    }

    public void arrive(double latitude, double longitude, BigDecimal distanceMeters, String selfieKey,
                       Instant purgeDueAt, Instant now) {
        if (arrivedAt != null) {
            throw new IllegalStateException("Already checked in");
        }
        this.arrivedAt = now;
        this.arrivalLatitude = latitude;
        this.arrivalLongitude = longitude;
        this.distanceMeters = distanceMeters;
        this.selfieKey = selfieKey;
        this.selfiePurgeDueAt = purgeDueAt;
        this.updatedAt = now;
    }

    /** "I'm safe": stops the escalation without claiming an arrival. */
    public void confirmSafe(Instant now) {
        if (safeConfirmedAt == null) {
            safeConfirmedAt = now;
        }
        updatedAt = now;
    }

    public void recordPing(Instant now) {
        pingedAt = now;
        updatedAt = now;
    }

    public void recordEscalation(Instant now) {
        escalatedAt = now;
        updatedAt = now;
    }

    /** Removes the stored selfie's key; the caller deletes the object. */
    public String purgeSelfie(Instant now) {
        String key = selfieKey;
        selfieKey = null;
        selfiePurgeDueAt = null;
        updatedAt = now;
        return key;
    }

    public boolean hasArrived() {
        return arrivedAt != null;
    }

    /** Accounted for: arrived, or said they are safe. The safety job leaves them alone. */
    public boolean isAccountedFor() {
        return arrivedAt != null || safeConfirmedAt != null;
    }

    public UUID getMeetupId() {
        return meetupId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getSafeConfirmedAt() {
        return safeConfirmedAt;
    }

    public Instant getPingedAt() {
        return pingedAt;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }
}
