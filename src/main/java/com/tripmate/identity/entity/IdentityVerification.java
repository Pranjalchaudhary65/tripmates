package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The record of one verification attempt. A PHONE row is written already
 * approved when a phone code is confirmed. A SELFIE_MATCH row holds its image
 * key only until {@code purgeDueAt}, whatever the outcome; see DESIGN.md §1
 * identity_verifications.
 */
@Entity
@Table(name = "identity_verifications")
public class IdentityVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private VerificationTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationStatus status;

    @Column(name = "similarity_score", precision = 4, scale = 3, updatable = false)
    private BigDecimal similarityScore;

    @Column(name = "selfie_image_key")
    private String selfieImageKey;

    @Column(name = "purge_due_at", updatable = false)
    private Instant purgeDueAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 30)
    private VerificationRejection rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected IdentityVerification() {
    }

    public static IdentityVerification phoneConfirmed(UUID userId, Instant now) {
        IdentityVerification verification = new IdentityVerification();
        verification.userId = userId;
        verification.tier = VerificationTier.PHONE;
        verification.status = VerificationStatus.APPROVED;
        verification.createdAt = now;
        verification.reviewedAt = now;
        return verification;
    }

    /**
     * A selfie as the liveness check decided it: approved, rejected, or
     * (status PENDING) sent to a reviewer.
     */
    public static IdentityVerification selfie(UUID userId, String imageKey, BigDecimal similarity,
                                              VerificationStatus status, VerificationRejection rejection,
                                              Duration retention, Instant now) {
        if ((status == VerificationStatus.REJECTED) != (rejection != null)) {
            throw new IllegalArgumentException("A rejection reason goes with REJECTED and nothing else");
        }
        IdentityVerification verification = new IdentityVerification();
        verification.userId = userId;
        verification.tier = VerificationTier.SELFIE_MATCH;
        verification.status = status;
        verification.similarityScore = similarity;
        verification.selfieImageKey = imageKey;
        verification.purgeDueAt = now.plus(retention);
        verification.rejectionReason = rejection;
        verification.createdAt = now;
        verification.reviewedAt = status == VerificationStatus.PENDING ? null : now;
        return verification;
    }

    public boolean isPending() {
        return status == VerificationStatus.PENDING;
    }

    /** A reviewer's decision on a flagged selfie. */
    public void decide(boolean approve, UUID reviewer, Instant now) {
        requirePending();
        this.status = approve ? VerificationStatus.APPROVED : VerificationStatus.REJECTED;
        this.rejectionReason = approve ? null : VerificationRejection.REVIEWER_REJECTED;
        this.reviewerId = reviewer;
        this.reviewedAt = now;
    }

    /** Ends a pending review without a reviewer: the photo it compared against changed, or time ran out. */
    public void withdraw(VerificationRejection reason, Instant now) {
        requirePending();
        this.status = VerificationStatus.REJECTED;
        this.rejectionReason = reason;
        this.reviewedAt = now;
    }

    /** The image is gone from storage; the row keeps the outcome and nothing else. */
    public void markPurged(Instant now) {
        this.selfieImageKey = null;
        this.purgedAt = now;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException("The verification has already been decided");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public VerificationTier getTier() {
        return tier;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public BigDecimal getSimilarityScore() {
        return similarityScore;
    }

    public String getSelfieImageKey() {
        return selfieImageKey;
    }

    public Instant getPurgeDueAt() {
        return purgeDueAt;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public VerificationRejection getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
