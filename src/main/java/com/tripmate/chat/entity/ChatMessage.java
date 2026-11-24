package com.tripmate.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One message in a conversation (DESIGN.md §4 chat_messages). Written once
 * and never changed: there is no edit and no delete, so nothing either
 * person said can be quietly rewritten after the other has read it.
 *
 * <p>A USER message holds only ciphertext. The text was stripped of markup
 * and had contact details redacted before it was sealed, so the unredacted
 * words exist nowhere at rest.
 */
@Entity
@Immutable
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(nullable = false, updatable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private MessageKind kind;

    @Column(name = "sender_id", updatable = false)
    private UUID senderId;

    @Column(name = "client_message_id", updatable = false)
    private UUID clientMessageId;

    @Column(name = "body_ciphertext", updatable = false)
    private byte[] bodyCiphertext;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_code", updatable = false, length = 30)
    private SystemCode systemCode;

    @Column(nullable = false, updatable = false)
    private boolean redacted;

    @Column(nullable = false, updatable = false)
    private boolean flagged;

    @Enumerated(EnumType.STRING)
    @Column(name = "flag_reason", updatable = false, length = 30)
    private FlagType flagReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatMessage() {
    }

    /**
     * @param flagReason the moderation flag the message carries, or null
     */
    public static ChatMessage fromUser(UUID conversationId, long seq, UUID senderId, UUID clientMessageId,
                                       byte[] bodyCiphertext, boolean redacted, FlagType flagReason, Instant now) {
        ChatMessage message = new ChatMessage();
        message.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        message.seq = seq;
        message.kind = MessageKind.USER;
        message.senderId = Objects.requireNonNull(senderId, "senderId");
        message.clientMessageId = Objects.requireNonNull(clientMessageId, "clientMessageId");
        message.bodyCiphertext = Objects.requireNonNull(bodyCiphertext, "bodyCiphertext");
        message.redacted = redacted;
        message.flagged = flagReason != null;
        message.flagReason = flagReason;
        message.createdAt = now;
        return message;
    }

    public static ChatMessage system(UUID conversationId, long seq, SystemCode code, Instant now) {
        ChatMessage message = new ChatMessage();
        message.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        message.seq = seq;
        message.kind = MessageKind.SYSTEM;
        message.systemCode = Objects.requireNonNull(code, "code");
        message.createdAt = now;
        return message;
    }

    /**
     * The additional data a USER message's ciphertext is bound to: its own
     * place in its own conversation, so a sealed body copied onto another
     * row does not open.
     */
    public static String encryptionContext(UUID conversationId, long seq) {
        return conversationId + ":" + seq;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public long getSeq() {
        return seq;
    }

    public MessageKind getKind() {
        return kind;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    public byte[] getBodyCiphertext() {
        return bodyCiphertext;
    }

    public SystemCode getSystemCode() {
        return systemCode;
    }

    public boolean isRedacted() {
        return redacted;
    }

    public FlagType getFlagReason() {
        return flagReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
