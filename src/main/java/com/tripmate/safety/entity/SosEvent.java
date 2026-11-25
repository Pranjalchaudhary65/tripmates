package com.tripmate.safety.entity;

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
 * One press of SOS (sos_events). OPEN until the emergency contact alert is
 * handed over (ESCALATED), and RESOLVED once the traveller says they are
 * safe again or a moderator closes it. Recording it never depends on the
 * alert: the row is committed first, and an alert that fails is retried.
 */
@Entity
@Table(name = "sos_events")
public class SosEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "client_sos_id", nullable = false, updatable = false)
    private UUID clientSosId;

    @Column(name = "triggered_at", nullable = false, updatable = false)
    private Instant triggeredAt;

    @Column(name = "rounded_latitude", updatable = false)
    private Double roundedLatitude;

    @Column(name = "rounded_longitude", updatable = false)
    private Double roundedLongitude;

    @Column(name = "last_known_location_ciphertext", updatable = false)
    private byte[] locationCiphertext;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "alert_attempts", nullable = false)
    private short alertAttempts;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected SosEvent() {
    }

    /** @param location null when the phone shared none */
    public static SosEvent raise(UUID tripId, UUID userId, UUID clientSosId, RoundedLocation location, Instant now) {
        SosEvent sos = new SosEvent();
        sos.tripId = tripId;
        sos.userId = userId;
        sos.clientSosId = clientSosId;
        sos.triggeredAt = now;
        sos.status = Status.OPEN;
        if (location != null) {
            sos.roundedLatitude = location.latitude();
            sos.roundedLongitude = location.longitude();
            sos.locationCiphertext = location.ciphertext();
        }
        return sos;
    }

    public void recordAlertAttempt() {
        alertAttempts++;
    }

    public void escalated(Instant now) {
        if (status == Status.OPEN) {
            status = Status.ESCALATED;
            escalatedAt = now;
        }
    }

    public void resolve(Instant now) {
        if (status != Status.RESOLVED) {
            status = Status.RESOLVED;
            resolvedAt = now;
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

    public UUID getClientSosId() {
        return clientSosId;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public byte[] getLocationCiphertext() {
        return locationCiphertext;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    /** Values match ck_sos_events_status. */
    public enum Status {
        OPEN, ESCALATED, RESOLVED
    }
}
