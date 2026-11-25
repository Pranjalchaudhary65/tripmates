package com.tripmate.settlement.entity;

/**
 * Values match ck_settlements_status (DESIGN.md §6, Settlement flow & state
 * machine). PROPOSED is the planner's suggestion; MARKED_PAID is the payer
 * saying "I sent it"; CONFIRMED is the payee saying "I got it", the only step
 * that writes the ledger. DISPUTED stops the automated flow. VOIDED is a
 * suggestion the planner replaced before anyone acted on it, because the
 * balances it was worked out from changed.
 */
public enum SettlementStatus {
    PROPOSED, MARKED_PAID, CONFIRMED, DISPUTED, VOIDED;

    /** Still waiting on one of its two people. */
    public boolean isOpen() {
        return this == PROPOSED || this == MARKED_PAID;
    }
}
