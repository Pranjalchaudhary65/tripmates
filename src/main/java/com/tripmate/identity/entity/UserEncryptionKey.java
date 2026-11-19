package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's data key, wrapped by the application KEK. The ID is assigned here
 * because it is part of the additional data the key is wrapped with, which
 * has to be known before the row is written. Rows are inserted once and only
 * ever changed by the shredding update in {@code UserEncryptionKeyRepository}.
 */
@Entity
@Table(name = "user_encryption_keys")
public class UserEncryptionKey implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "wrapped_dek", nullable = false, updatable = false)
    private byte[] wrappedDek;

    @Column(nullable = false, updatable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at", updatable = false)
    private Instant revokedAt;

    @Transient
    private boolean isNew = true;

    protected UserEncryptionKey() {
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public static UserEncryptionKey create(UUID id, UUID userId, byte[] wrappedDek, Instant now) {
        UserEncryptionKey key = new UserEncryptionKey();
        key.id = Objects.requireNonNull(id, "id");
        key.userId = Objects.requireNonNull(userId, "userId");
        key.wrappedDek = Objects.requireNonNull(wrappedDek, "wrappedDek").clone();
        key.active = true;
        key.createdAt = now;
        return key;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getWrappedDek() {
        return wrappedDek.clone();
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
