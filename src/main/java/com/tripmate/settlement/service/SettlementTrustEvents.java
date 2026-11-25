package com.tripmate.settlement.service;

import com.tripmate.platform.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The only fact settle-up hands the trust score, through the outbox: someone
 * left a settlement undone long enough to count as ghosting it. Settlement
 * never calls the trust score and never imports it; the trust engine reads
 * this event when it is built. Nothing here carries an amount or a
 * description.
 */
@Component
public class SettlementTrustEvents {

    public static final String AGGREGATE_USER = "USER";

    public static final String SETTLEMENT_OVERDUE = "SETTLEMENT_OVERDUE";

    private final OutboxPublisher outbox;

    public SettlementTrustEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settlementOverdue(UUID userId, UUID tripId, OverdueRole role) {
        outbox.publish(AGGREGATE_USER, userId, SETTLEMENT_OVERDUE,
                Map.of("userId", userId, "tripId", tripId, "role", role.name()));
    }

    /** Which side of the payment was left undone. */
    public enum OverdueRole {
        /** Never marked a suggested payment as sent. */
        PAYER,
        /** Never confirmed a payment marked as sent to them. */
        PAYEE
    }
}
