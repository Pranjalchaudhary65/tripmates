package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of login_history. Written once and never changed: there are no
 * setters, and {@code @Immutable} makes Hibernate ignore any change to a
 * loaded instance instead of flushing it.
 */
@Entity
@Immutable
@Table(name = "login_history")
public class LoginAttempt {

    /** Longest User-Agent kept; the header is client-controlled and otherwise only bounded by Tomcat's 8 KB. */
    public static final int MAX_USER_AGENT_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "email_attempted", nullable = false, updatable = false, columnDefinition = "citext")
    private String emailAttempted;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address", nullable = false, updatable = false, columnDefinition = "inet")
    private InetAddress ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "device_id_hash", length = 64, updatable = false)
    private String deviceIdHash;

    @Column(nullable = false, updatable = false)
    private boolean success;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30, updatable = false)
    private LoginFailureReason failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoginAttempt() {
    }

    public static LoginAttempt succeeded(UUID userId, String email, InetAddress ip, String userAgent,
                                         String deviceIdHash, Instant now) {
        LoginAttempt attempt = record(userId, email, ip, userAgent, deviceIdHash, now);
        attempt.userId = Objects.requireNonNull(userId, "userId");
        attempt.success = true;
        return attempt;
    }

    public static LoginAttempt failed(UUID userId, String email, InetAddress ip, String userAgent,
                                      String deviceIdHash, LoginFailureReason reason, Instant now) {
        LoginAttempt attempt = record(userId, email, ip, userAgent, deviceIdHash, now);
        attempt.success = false;
        attempt.failureReason = Objects.requireNonNull(reason, "reason");
        return attempt;
    }

    private static LoginAttempt record(UUID userId, String email, InetAddress ip, String userAgent,
                                       String deviceIdHash, Instant now) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.userId = userId;
        attempt.emailAttempted = Objects.requireNonNull(email, "email");
        attempt.ipAddress = Objects.requireNonNull(ip, "ip");
        attempt.userAgent = userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH
                ? userAgent : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        attempt.deviceIdHash = deviceIdHash;
        attempt.createdAt = Objects.requireNonNull(now, "now");
        return attempt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmailAttempted() {
        return emailAttempted;
    }

    public InetAddress getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getDeviceIdHash() {
        return deviceIdHash;
    }

    public boolean isSuccess() {
        return success;
    }

    public LoginFailureReason getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
