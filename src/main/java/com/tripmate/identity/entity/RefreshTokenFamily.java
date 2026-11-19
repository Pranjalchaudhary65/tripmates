package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One signed-in session: the family every refresh token from one login
 * descends from. Rows are only ever changed by the guarded bulk updates in
 * {@code RefreshTokenFamilyRepository}, each conditional on the family still
 * being live, so a refresh racing a revocation can never write a revoked row
 * back to live. There are no setters for that reason.
 *
 * <p>The ID is assigned here rather than by the database because it is
 * part of the refresh token, which has to be built before the row is
 * flushed. {@link Persistable} tells Spring Data the row is new, so saving it
 * is a plain INSERT instead of a SELECT to find out.
 */
@Entity
@Table(name = "refresh_token_families")
public class RefreshTokenFamily implements Persistable<UUID> {

    /** Same bound login_history uses; the header is client-controlled. */
    public static final int MAX_USER_AGENT_LENGTH = 512;

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "device_name", nullable = false, length = 64, updatable = false)
    private String deviceName;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address", nullable = false, updatable = false, columnDefinition = "inet")
    private InetAddress ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "last_ip_address", nullable = false, columnDefinition = "inet")
    private InetAddress lastIpAddress;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 30)
    private SessionRevocationReason revokedReason;

    @Column(name = "second_factor_verified", nullable = false, updatable = false)
    private boolean secondFactorVerified;

    @Transient
    private boolean isNew = true;

    protected RefreshTokenFamily() {
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

    public static RefreshTokenFamily open(UUID userId, String deviceName, InetAddress ip, String userAgent,
                                          Instant now, Instant expiresAt, boolean secondFactorVerified) {
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("A session must expire after it starts");
        }
        RefreshTokenFamily family = new RefreshTokenFamily();
        family.id = UUID.randomUUID();
        family.userId = Objects.requireNonNull(userId, "userId");
        family.deviceName = Objects.requireNonNull(deviceName, "deviceName");
        family.ipAddress = Objects.requireNonNull(ip, "ip");
        family.userAgent = userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH
                ? userAgent : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        family.lastIpAddress = ip;
        family.lastUsedAt = now;
        family.expiresAt = expiresAt;
        family.createdAt = now;
        family.updatedAt = now;
        family.secondFactorVerified = secondFactorVerified;
        return family;
    }

    /** Not revoked and not past its absolute expiry at {@code now}. */
    public boolean isLiveAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public InetAddress getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public InetAddress getLastIpAddress() {
        return lastIpAddress;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public SessionRevocationReason getRevokedReason() {
        return revokedReason;
    }

    /** The login behind this session passed TOTP or a backup code; fixed for the session's life. */
    public boolean isSecondFactorVerified() {
        return secondFactorVerified;
    }
}
