package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One single-use 2FA backup code, stored as a keyed hash only. */
@Entity
@Table(name = "totp_backup_codes")
public class TotpBackupCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TotpBackupCode() {
    }

    public static TotpBackupCode issue(UUID userId, String codeHash, Instant now) {
        TotpBackupCode code = new TotpBackupCode();
        code.userId = Objects.requireNonNull(userId, "userId");
        code.codeHash = Objects.requireNonNull(codeHash, "codeHash");
        code.createdAt = now;
        return code;
    }

    public void markUsed(Instant now) {
        if (usedAt != null) {
            throw new IllegalStateException("A backup code is used once only");
        }
        this.usedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
