package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** The UPI ID a user wants to be paid at (upi_ids). One per user; the ID itself is ciphertext only. */
@Entity
@Table(name = "upi_ids")
public class UpiId {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "vpa_ciphertext", nullable = false)
    private byte[] vpaCiphertext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UpiId() {
    }

    public static UpiId create(UUID userId, byte[] vpaCiphertext, Instant now) {
        UpiId upiId = new UpiId();
        upiId.userId = userId;
        upiId.createdAt = now;
        upiId.replace(vpaCiphertext, now);
        return upiId;
    }

    public void replace(byte[] vpaCiphertext, Instant now) {
        this.vpaCiphertext = vpaCiphertext;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getVpaCiphertext() {
        return vpaCiphertext;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
