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
 * One double-entry transaction on a trip's ledger (ledger_transactions): an
 * expense, the reversal correcting one, or a confirmed settlement. Its legs
 * are {@link LedgerEntry} rows; the database refuses to commit it unless
 * they balance. Append-only: corrections are new transactions, never edits.
 */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private LedgerKind kind;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false, length = 120)
    private String description;

    @Column(name = "paid_by_user_id", updatable = false)
    private UUID paidByUserId;

    @Column(name = "recorded_by_user_id", nullable = false, updatable = false)
    private UUID recordedByUserId;

    @Column(name = "reverses_id", updatable = false)
    private UUID reversesId;

    @Column(name = "settlement_id", updatable = false)
    private UUID settlementId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    /** The payer records what they paid; the shares are the legs written with it. */
    public static LedgerTransaction expense(UUID tripId, UUID payerId, long amountPaise, String description,
                                            String idempotencyKey, String fingerprint, Instant now) {
        LedgerTransaction transaction = base(tripId, LedgerKind.EXPENSE, amountPaise, payerId, idempotencyKey,
                fingerprint, now);
        transaction.description = description;
        transaction.paidByUserId = payerId;
        return transaction;
    }

    public static LedgerTransaction reversalOf(LedgerTransaction expense, UUID recordedBy, Instant now) {
        if (expense.kind != LedgerKind.EXPENSE) {
            throw new IllegalArgumentException("Only an expense can be reversed");
        }
        LedgerTransaction transaction = base(expense.tripId, LedgerKind.EXPENSE_REVERSAL, expense.amountPaise,
                recordedBy, "reversal:" + expense.id, "reversal:" + expense.id, now);
        transaction.reversesId = expense.id;
        return transaction;
    }

    /** Recorded by the payee confirming they were paid. */
    public static LedgerTransaction settlement(Settlement settlement, Instant now) {
        LedgerTransaction transaction = base(settlement.getTripId(), LedgerKind.SETTLEMENT,
                settlement.getAmountPaise(), settlement.getToUserId(), "settlement:" + settlement.getId(),
                "settlement:" + settlement.getId(), now);
        transaction.settlementId = settlement.getId();
        return transaction;
    }

    private static LedgerTransaction base(UUID tripId, LedgerKind kind, long amountPaise, UUID recordedBy,
                                          String idempotencyKey, String fingerprint, Instant now) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("A ledger transaction moves a positive amount");
        }
        LedgerTransaction transaction = new LedgerTransaction();
        transaction.tripId = tripId;
        transaction.kind = kind;
        transaction.amountPaise = amountPaise;
        transaction.currency = Settlement.CURRENCY;
        transaction.recordedByUserId = recordedBy;
        transaction.idempotencyKey = idempotencyKey;
        transaction.requestFingerprint = fingerprint;
        transaction.createdAt = now;
        return transaction;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public LedgerKind getKind() {
        return kind;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getDescription() {
        return description;
    }

    public UUID getPaidByUserId() {
        return paidByUserId;
    }

    public UUID getRecordedByUserId() {
        return recordedByUserId;
    }

    public UUID getReversesId() {
        return reversesId;
    }

    public UUID getSettlementId() {
        return settlementId;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
