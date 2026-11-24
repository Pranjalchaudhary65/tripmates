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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One reason a message was flagged, for the moderation queue (DESIGN.md §4 moderation_flags). */
@Entity
@Immutable
@Table(name = "moderation_flags")
public class ModerationFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chat_message_id", nullable = false, updatable = false)
    private UUID chatMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "flag_type", nullable = false, updatable = false, length = 30)
    private FlagType flagType;

    @Column(nullable = false, updatable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_action", nullable = false, updatable = false, length = 30)
    private AutoAction autoAction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModerationFlag() {
    }

    public static ModerationFlag raise(UUID chatMessageId, FlagType type, BigDecimal confidence,
                                       AutoAction action, Instant now) {
        ModerationFlag flag = new ModerationFlag();
        flag.chatMessageId = chatMessageId;
        flag.flagType = type;
        flag.confidence = confidence;
        flag.autoAction = action;
        flag.createdAt = now;
        return flag;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChatMessageId() {
        return chatMessageId;
    }

    public FlagType getFlagType() {
        return flagType;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public AutoAction getAutoAction() {
        return autoAction;
    }
}
