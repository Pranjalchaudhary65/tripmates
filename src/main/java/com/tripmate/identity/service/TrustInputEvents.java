package com.tripmate.identity.service;

import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.platform.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The identity facts the trust score is computed from, written to the outbox
 * in the transaction that changes them (DESIGN.md, Trust Score Engine spec).
 * Identity never calls the trust module; the outbox relay turns these rows
 * into trust_score_events.
 */
@Component
public class TrustInputEvents {

    public static final String AGGREGATE_USER = "USER";

    public static final String VERIFICATION_TIER_CHANGE = "VERIFICATION_TIER_CHANGE";

    /** The vouched user's vouch bonus is recomputed from the live count; this only says it may have changed. */
    public static final String VOUCH_GIVEN = "VOUCH_GIVEN";

    public static final String VOUCH_REVOKED = "VOUCH_REVOKED";

    /** One per active voucher of an account that was suspended, banned, or crossed the report threshold. */
    public static final String VOUCH_PENALIZED = "VOUCH_PENALIZED";

    private final OutboxPublisher outbox;

    public TrustInputEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    /** Writes nothing when the tier did not actually move. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void tierChanged(UUID userId, VerificationTier before, VerificationTier after) {
        if (before == after) {
            return;
        }
        outbox.publish(AGGREGATE_USER, userId, VERIFICATION_TIER_CHANGE,
                Map.of("userId", userId, "from", before.name(), "to", after.name()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void vouchGiven(UUID vouchId, UUID voucherUserId, UUID vouchedUserId) {
        outbox.publish(AGGREGATE_USER, vouchedUserId, VOUCH_GIVEN,
                Map.of("vouchId", vouchId, "voucherUserId", voucherUserId, "vouchedUserId", vouchedUserId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void vouchRevoked(UUID vouchId, UUID voucherUserId, UUID vouchedUserId) {
        outbox.publish(AGGREGATE_USER, vouchedUserId, VOUCH_REVOKED,
                Map.of("vouchId", vouchId, "voucherUserId", voucherUserId, "vouchedUserId", vouchedUserId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void vouchPenalized(UUID voucherUserId, UUID penalizedUserId) {
        outbox.publish(AGGREGATE_USER, voucherUserId, VOUCH_PENALIZED,
                Map.of("voucherUserId", voucherUserId, "vouchedUserId", penalizedUserId));
    }
}
