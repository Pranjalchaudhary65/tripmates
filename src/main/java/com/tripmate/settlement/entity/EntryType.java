package com.tripmate.settlement.entity;

/**
 * Values match ck_ledger_entries_type. A DEBIT raises what the others owe the
 * account (they paid out); a CREDIT lowers it (their share, or money they
 * were paid back).
 */
public enum EntryType {
    DEBIT, CREDIT;

    public EntryType opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    /** The leg's effect on its account's net balance. */
    public long signed(long amountPaise) {
        return this == DEBIT ? amountPaise : -amountPaise;
    }
}
