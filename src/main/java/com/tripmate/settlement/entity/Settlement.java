package com.tripmate.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One suggested transfer between two travellers to settle a trip (DESIGN.md
 * §6 settlements). It records who should pay whom and how far that has got;
 * it never holds or moves money. Each method is one arrow of the state
 * machine and refuses to run from any other state, so the service cannot
 * skip a step by mistake. {@code version} makes two changes that read the
 * same state unable to both commit.
 */
@Entity
@Table(name = "settlements")
public class Settlement {

    public static final String CURRENCY = "INR";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "from_user_id", nullable = false, updatable = false)
    private UUID fromUserId;

    @Column(name = "to_user_id", nullable = false, updatable = false)
    private UUID toUserId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(name = "marked_paid_at")
    private Instant markedPaidAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Settlement() {
    }

    public static Settlement propose(UUID tripId, UUID fromUserId, UUID toUserId, long amountPaise, Instant now) {
        if (fromUserId.equals(toUserId) || amountPaise <= 0) {
            throw new IllegalArgumentException("A settlement is a positive amount between two different people");
        }
        Settlement settlement = new Settlement();
        settlement.tripId = tripId;
        settlement.fromUserId = fromUserId;
        settlement.toUserId = toUserId;
        settlement.amountPaise = amountPaise;
        settlement.currency = CURRENCY;
        settlement.status = SettlementStatus.PROPOSED;
        settlement.createdAt = now;
        settlement.updatedAt = now;
        return settlement;
    }

    /** The payer says they sent it. */
    public void markPaid(Instant now) {
        require(SettlementStatus.PROPOSED);
        status = SettlementStatus.MARKED_PAID;
        markedPaidAt = now;
        updatedAt = now;
    }

    /** The payee says they received it. Only from MARKED_PAID: nobody confirms a payment not yet sent. */
    public void confirm(Instant now) {
        require(SettlementStatus.MARKED_PAID);
        status = SettlementStatus.CONFIRMED;
        confirmedAt = now;
        updatedAt = now;
    }

    public void dispute(Instant now) {
        if (!status.isOpen()) {
            throw new IllegalStateException("Only an open settlement can be disputed, not " + status);
        }
        status = SettlementStatus.DISPUTED;
        closedAt = now;
        updatedAt = now;
    }

    /** Replaced by a newer plan before either person acted on it. */
    public void voidForNewPlan(Instant now) {
        require(SettlementStatus.PROPOSED);
        status = SettlementStatus.VOIDED;
        closedAt = now;
        updatedAt = now;
    }

    public boolean isBetween(UUID from, UUID to, long amount) {
        return fromUserId.equals(from) && toUserId.equals(to) && amountPaise == amount;
    }

    private void require(SettlementStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getFromUserId() {
        return fromUserId;
    }

    public UUID getToUserId() {
        return toUserId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public Instant getMarkedPaidAt() {
        return markedPaidAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
