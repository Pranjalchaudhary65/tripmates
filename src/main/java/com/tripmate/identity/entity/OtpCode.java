package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One issued one-time code. Only a keyed hash of the code is held; the
 * plaintext exists in memory just long enough to be emailed.
 */
@Entity
@Table(name = "otp_codes")
public class OtpCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private OtpChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private OtpPurpose purpose;

    @Column(name = "code_hash", nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempt_count", nullable = false)
    private short attemptCount;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private short maxAttempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OtpCode() {
    }

    public static OtpCode issue(UUID userId, OtpChannel channel, OtpPurpose purpose, String codeHash,
                                Duration ttl, int maxAttempts, Instant now) {
        OtpCode otp = new OtpCode();
        otp.userId = userId;
        otp.channel = channel;
        otp.purpose = purpose;
        otp.codeHash = codeHash;
        otp.expiresAt = now.plus(ttl);
        otp.attemptCount = 0;
        otp.maxAttempts = (short) maxAttempts;
        otp.createdAt = now;
        return otp;
    }

    /** Not yet used, not expired, and attempts left. */
    public boolean isRedeemable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt) && attemptCount < maxAttempts;
    }

    /** Counted before the comparison, so a correct guess on the last attempt still uses that attempt. */
    public void recordAttempt() {
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("No attempts left on this code");
        }
        attemptCount++;
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    /** Ends a code early because a newer one replaced it. */
    public void supersede(Instant now) {
        if (expiresAt.isAfter(now)) {
            this.expiresAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public OtpChannel getChannel() {
        return channel;
    }

    public OtpPurpose getPurpose() {
        return purpose;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public short getAttemptCount() {
        return attemptCount;
    }

    public short getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
