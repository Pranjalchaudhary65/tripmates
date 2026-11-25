package com.tripmate.settlement.entity;

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
import java.util.UUID;

/**
 * One change of a settlement's state (settlement_state_transitions).
 * Append-only: written once, never changed, and the table refuses UPDATE and
 * DELETE whatever the code does.
 */
@Entity
@Immutable
@Table(name = "settlement_state_transitions")
public class SettlementTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 20)
    private SettlementStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private SettlementStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    private Reason reason;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SettlementTransition() {
    }

    /**
     * @param fromStatus null when the settlement is created
     * @param actorId    null for the planner's own changes
     */
    public static SettlementTransition record(UUID settlementId, SettlementStatus fromStatus,
                                              SettlementStatus toStatus, Reason reason, UUID actorId, Instant now) {
        SettlementTransition transition = new SettlementTransition();
        transition.settlementId = settlementId;
        transition.fromStatus = fromStatus;
        transition.toStatus = toStatus;
        transition.reason = reason;
        transition.actorId = actorId;
        transition.createdAt = now;
        return transition;
    }

    public UUID getSettlementId() {
        return settlementId;
    }

    public SettlementStatus getFromStatus() {
        return fromStatus;
    }

    public SettlementStatus getToStatus() {
        return toStatus;
    }

    public Reason getReason() {
        return reason;
    }

    public UUID getActorId() {
        return actorId;
    }

    /** Values match ck_settlement_transitions_reason. */
    public enum Reason {
        PLANNED, MARKED_PAID, CONFIRMED, DISPUTED, PLAN_REPLACED
    }
}
