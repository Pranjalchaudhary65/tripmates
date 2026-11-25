package com.tripmate.safety.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One "are you safe?" check-in on a live trip (checkins). PENDING until the
 * traveller answers (CONFIRMED), stops sharing (CANCELLED), or lets it pass
 * long enough to alert their emergency contact (MISSED).
 */
@Entity
@Table(name = "checkins")
public class Checkin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "pinged_at")
    private Instant pingedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "rounded_latitude")
    private Double roundedLatitude;

    @Column(name = "rounded_longitude")
    private Double roundedLongitude;

    @Column(name = "location_ciphertext")
    private byte[] locationCiphertext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Checkin() {
    }

    public static Checkin due(UUID tripId, UUID userId, Instant at, Instant now) {
        Checkin checkin = new Checkin();
        checkin.tripId = tripId;
        checkin.userId = userId;
        checkin.scheduledAt = at;
        checkin.status = Status.PENDING;
        checkin.createdAt = now;
        return checkin;
    }

    /**
     * The traveller says they are safe.
     *
     * @param location null when they shared none
     */
    public void confirm(Instant now, RoundedLocation location) {
        requirePending();
        status = Status.CONFIRMED;
        respondedAt = now;
        if (location != null) {
            roundedLatitude = location.latitude();
            roundedLongitude = location.longitude();
            locationCiphertext = location.ciphertext();
        }
    }

    public void cancel() {
        requirePending();
        status = Status.CANCELLED;
    }

    public void recordPing(Instant now) {
        pingedAt = now;
    }

    /** Their emergency contact was alerted; no answer came in time. */
    public void miss(Instant now) {
        requirePending();
        status = Status.MISSED;
        escalatedAt = now;
    }

    public void reschedule(Instant at) {
        requirePending();
        scheduledAt = at;
        pingedAt = null;
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Check-in is " + status);
        }
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

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Instant getPingedAt() {
        return pingedAt;
    }

    public Double getRoundedLatitude() {
        return roundedLatitude;
    }

    public Double getRoundedLongitude() {
        return roundedLongitude;
    }

    public byte[] getLocationCiphertext() {
        return locationCiphertext;
    }

    /** Values match ck_checkins_status. */
    public enum Status {
        PENDING, CONFIRMED, MISSED, CANCELLED
    }
}
