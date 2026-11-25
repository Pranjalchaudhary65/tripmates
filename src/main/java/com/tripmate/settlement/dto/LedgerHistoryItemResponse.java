package com.tripmate.settlement.dto;

import com.tripmate.settlement.entity.LedgerKind;

import java.time.Instant;
import java.util.UUID;

/**
 * One transaction in a trip's ledger history.
 *
 * @param paidBy        EXPENSE only
 * @param from          SETTLEMENT only: who paid
 * @param to            SETTLEMENT only: who was paid
 * @param reversesId    EXPENSE_REVERSAL only: the expense it corrects
 * @param myEffectPaise how it moved the caller's balance; positive means more is owed to them
 */
public record LedgerHistoryItemResponse(UUID id, LedgerKind kind, Instant createdAt, String description,
                                        long amountPaise, LedgerResponse.Person paidBy, LedgerResponse.Person from,
                                        LedgerResponse.Person to, UUID reversesId, long myEffectPaise) {
}
