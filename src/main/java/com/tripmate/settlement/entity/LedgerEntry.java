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
 * One leg of a ledger transaction (ledger_entries): an amount debited or
 * credited to one traveller's account on one trip. The account is a user ID,
 * not a wallet; nobody can withdraw or spend anything here. Append-only.
 */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "account_user_id", nullable = false, updatable = false)
    private UUID accountUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 6)
    private EntryType entryType;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public static LedgerEntry leg(LedgerTransaction transaction, UUID accountUserId, EntryType type,
                                  long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("A leg moves a positive amount");
        }
        LedgerEntry entry = new LedgerEntry();
        entry.transactionId = transaction.getId();
        entry.tripId = transaction.getTripId();
        entry.accountUserId = accountUserId;
        entry.entryType = type;
        entry.amountPaise = amountPaise;
        entry.currency = Settlement.CURRENCY;
        entry.createdAt = transaction.getCreatedAt();
        return entry;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getAccountUserId() {
        return accountUserId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public long getAmountPaise() {
        return amountPaise;
    }
}
