package com.tripmate.safety.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One traveller sharing one trip with their emergency contact
 * (itinerary_shares): how often they want to be asked whether they are
 * safe, and an optional note for the contact. Stopping is free and keeps the
 * row, so the history of when someone was covered stays readable.
 */
@Entity
@Table(name = "itinerary_shares")
public class ItineraryShare {

    /** Values match ck_itinerary_shares_interval. */
    public static final Set<Integer> INTERVALS = Set.of(3, 6, 12, 24);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "check_in_every_hours", nullable = false)
    private short checkInEveryHours;

    @Column(name = "note_ciphertext")
    private byte[] noteCiphertext;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ItineraryShare() {
    }

    public static ItineraryShare start(UUID tripId, UUID userId, int everyHours, byte[] noteCiphertext, Instant now) {
        ItineraryShare share = new ItineraryShare();
        share.tripId = tripId;
        share.userId = userId;
        share.restart(everyHours, noteCiphertext, now);
        return share;
    }

    /** Starts again, or changes the interval or note while active. */
    public void restart(int everyHours, byte[] noteCiphertext, Instant now) {
        if (!INTERVALS.contains(everyHours)) {
            throw new IllegalArgumentException("Unsupported check-in interval");
        }
        if (!isActive()) {
            startedAt = now;
        }
        checkInEveryHours = (short) everyHours;
        this.noteCiphertext = noteCiphertext;
        stoppedAt = null;
        updatedAt = now;
    }

    public void stop(Instant now) {
        stoppedAt = now;
        updatedAt = now;
    }

    public boolean isActive() {
        return startedAt != null && stoppedAt == null;
    }

    public Duration interval() {
        return Duration.ofHours(checkInEveryHours);
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getCheckInEveryHours() {
        return checkInEveryHours;
    }

    public byte[] getNoteCiphertext() {
        return noteCiphertext;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
