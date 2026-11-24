package com.tripmate.chat.service;

import com.tripmate.chat.entity.FlagType;
import com.tripmate.platform.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The chat facts the trust score is computed from, written to the outbox in
 * the transaction that stores the message (DESIGN.md, Trust Score Engine
 * spec). Chat never calls the trust module; the payload names the message,
 * never its text.
 */
@Component
public class ChatTrustEvents {

    public static final String AGGREGATE_USER = "USER";

    public static final String CHAT_MESSAGE_FLAGGED = "CHAT_MESSAGE_FLAGGED";

    private final OutboxPublisher outbox;

    public ChatTrustEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void messageFlagged(UUID senderId, UUID messageId, FlagType type, BigDecimal confidence) {
        outbox.publish(AGGREGATE_USER, senderId, CHAT_MESSAGE_FLAGGED, Map.of("userId", senderId,
                "messageId", messageId, "flagType", type.name(), "confidence", confidence.toPlainString()));
    }
}
