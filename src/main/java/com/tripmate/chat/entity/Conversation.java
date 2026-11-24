package com.tripmate.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The masked chat between a trip's host and one accepted companion
 * (DESIGN.md §4 conversations). Exactly two people are in it, and both are
 * on the row itself, so deciding who may read or write it needs nothing else.
 *
 * <p>Every change to a conversation is made under its row lock
 * ({@code ConversationRepository.findByIdForUpdate}), which is what makes
 * message numbers gap-free and never shared: {@link #nextSeq} reads and
 * bumps {@code last_seq} while no one else can.
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "host_user_id", nullable = false, updatable = false)
    private UUID hostUserId;

    @Column(name = "requester_user_id", nullable = false, updatable = false)
    private UUID requesterUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationStatus status;

    @Column(name = "contact_unlocked_at")
    private Instant contactUnlockedAt;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "host_read_seq", nullable = false)
    private long hostReadSeq;

    @Column(name = "requester_read_seq", nullable = false)
    private long requesterReadSeq;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    public static Conversation open(UUID tripId, UUID hostUserId, UUID requesterUserId, Instant now) {
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(hostUserId, "hostUserId");
        Objects.requireNonNull(requesterUserId, "requesterUserId");
        if (hostUserId.equals(requesterUserId)) {
            throw new IllegalArgumentException("A conversation needs two different people");
        }
        Conversation conversation = new Conversation();
        conversation.tripId = tripId;
        conversation.hostUserId = hostUserId;
        conversation.requesterUserId = requesterUserId;
        conversation.status = ConversationStatus.ACTIVE;
        conversation.createdAt = now;
        conversation.updatedAt = now;
        return conversation;
    }

    public boolean includes(UUID userId) {
        return hostUserId.equals(userId) || requesterUserId.equals(userId);
    }

    /** The person in this conversation who is not {@code userId}, who must be in it. */
    public UUID otherThan(UUID userId) {
        requireIncluded(userId);
        return hostUserId.equals(userId) ? requesterUserId : hostUserId;
    }

    public boolean isActive() {
        return status == ConversationStatus.ACTIVE;
    }

    public boolean isContactUnlocked() {
        return contactUnlockedAt != null;
    }

    /**
     * Takes the number for a new message from {@code senderId}. The sender
     * has read everything up to their own message by sending it.
     */
    public long nextSeq(UUID senderId, Instant now) {
        requireIncluded(senderId);
        lastSeq++;
        lastMessageAt = now;
        markRead(senderId, lastSeq, now);
        return lastSeq;
    }

    /** Takes the number for a SYSTEM message, which nobody has read yet. */
    public long nextSystemSeq(Instant now) {
        lastSeq++;
        lastMessageAt = now;
        updatedAt = now;
        return lastSeq;
    }

    /**
     * Moves the reader's marker forward to {@code upToSeq}, never back and
     * never past the last message.
     *
     * @return whether the marker moved
     */
    public boolean markRead(UUID readerId, long upToSeq, Instant now) {
        requireIncluded(readerId);
        long target = Math.min(Math.max(upToSeq, 0), lastSeq);
        if (target <= readSeqOf(readerId)) {
            return false;
        }
        if (hostUserId.equals(readerId)) {
            hostReadSeq = target;
        } else {
            requesterReadSeq = target;
        }
        updatedAt = now;
        return true;
    }

    public long readSeqOf(UUID userId) {
        requireIncluded(userId);
        return hostUserId.equals(userId) ? hostReadSeq : requesterReadSeq;
    }

    /** Called when the trip locks for this companion; repeating it changes nothing. */
    public boolean unlockContact(Instant now) {
        if (contactUnlockedAt != null) {
            return false;
        }
        contactUnlockedAt = now;
        updatedAt = now;
        return true;
    }

    /** No new messages from either side; the history stays readable. Repeating it changes nothing. */
    public boolean close(Instant now) {
        if (status == ConversationStatus.CLOSED) {
            return false;
        }
        status = ConversationStatus.CLOSED;
        updatedAt = now;
        return true;
    }

    private void requireIncluded(UUID userId) {
        if (!includes(userId)) {
            throw new IllegalArgumentException("Not a person in this conversation");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getHostUserId() {
        return hostUserId;
    }

    public UUID getRequesterUserId() {
        return requesterUserId;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public Instant getContactUnlockedAt() {
        return contactUnlockedAt;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
