package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One account vouching for another. There is one row per pair for good
 * (uq_vouches_pair): revoking sets {@code revokedAt}, and vouching again
 * reopens the same row.
 */
@Entity
@Table(name = "vouches")
public class Vouch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "voucher_user_id", nullable = false, updatable = false)
    private UUID voucherUserId;

    @Column(name = "vouched_user_id", nullable = false, updatable = false)
    private UUID vouchedUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Vouch() {
    }

    public static Vouch give(UUID voucherUserId, UUID vouchedUserId, Instant now) {
        if (voucherUserId.equals(vouchedUserId)) {
            throw new IllegalArgumentException("An account cannot vouch for itself");
        }
        Vouch vouch = new Vouch();
        vouch.voucherUserId = voucherUserId;
        vouch.vouchedUserId = vouchedUserId;
        vouch.createdAt = now;
        return vouch;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke(Instant now) {
        if (!isActive()) {
            throw new IllegalStateException("The vouch is already revoked");
        }
        this.revokedAt = now;
    }

    /** Vouching again for someone after revoking: the vouch counts from now, not from the first time. */
    public void renew(Instant now) {
        if (isActive()) {
            throw new IllegalStateException("The vouch is already active");
        }
        this.createdAt = now;
        this.revokedAt = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVoucherUserId() {
        return voucherUserId;
    }

    public UUID getVouchedUserId() {
        return vouchedUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
