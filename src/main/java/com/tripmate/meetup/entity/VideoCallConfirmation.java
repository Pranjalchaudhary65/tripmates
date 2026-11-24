package com.tripmate.meetup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Each side's confirmation that the in-app video call happened. A mock of a
 * real call: both people press a button. It still needs both of them, so
 * neither can skip the rung for the other.
 */
@Entity
@Table(name = "video_call_confirmations")
public class VideoCallConfirmation {

    @Id
    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "host_confirmed_at")
    private Instant hostConfirmedAt;

    @Column(name = "companion_confirmed_at")
    private Instant companionConfirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VideoCallConfirmation() {
    }

    public static VideoCallConfirmation start(UUID memberId, Instant now) {
        VideoCallConfirmation confirmation = new VideoCallConfirmation();
        confirmation.memberId = memberId;
        confirmation.createdAt = now;
        confirmation.updatedAt = now;
        return confirmation;
    }

    /** Records one side; repeating it keeps the first time. */
    public void confirm(boolean byHost, Instant now) {
        if (byHost && hostConfirmedAt == null) {
            hostConfirmedAt = now;
        } else if (!byHost && companionConfirmedAt == null) {
            companionConfirmedAt = now;
        }
        updatedAt = now;
    }

    public boolean isConfirmedBy(boolean host) {
        return host ? hostConfirmedAt != null : companionConfirmedAt != null;
    }

    public boolean isComplete() {
        return hostConfirmedAt != null && companionConfirmedAt != null;
    }
}
