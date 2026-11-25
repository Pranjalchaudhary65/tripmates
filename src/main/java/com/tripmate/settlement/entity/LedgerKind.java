package com.tripmate.settlement.entity;

/** Values match ck_ledger_transactions_kind. */
public enum LedgerKind {
    /** Someone paid for something and others share the cost. */
    EXPENSE,
    /** The correction of an expense: every leg of it turned round. */
    EXPENSE_REVERSAL,
    /** A debt confirmed as paid between two travellers, over UPI or otherwise, outside the platform. */
    SETTLEMENT
}
