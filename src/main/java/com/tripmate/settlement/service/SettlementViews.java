package com.tripmate.settlement.service;

import com.tripmate.settlement.entity.LedgerKind;
import com.tripmate.settlement.entity.SettlementStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the ledger service hands the controllers. Every amount is whole
 * paise; every balance is "owed to them" when positive and "they owe" when
 * negative. Nothing here is a wallet balance: it is what travellers owe one
 * another, from expenses they recorded themselves.
 */
public final class SettlementViews {

    private SettlementViews() {
    }

    public enum Role {
        HOST, COMPANION
    }

    /** The caller's part in a settlement. */
    public enum Part {
        PAYER, PAYEE, NONE
    }

    /**
     * The settle-up screen in one call.
     *
     * @param open        expenses can be added: the trip is locked and the caller is still on it
     * @param settlements every settlement still open or disputed, and the ones already confirmed
     * @param settledUp   nobody owes anyone anything and nothing is waiting to be confirmed
     */
    public record LedgerView(TripRef trip, boolean locked, boolean open, String currency, List<Member> members,
                             long myNetPaise, long totalSpentPaise, List<SettlementView> settlements,
                             List<ExpenseView> recentExpenses, boolean settledUp) {
    }

    public record TripRef(UUID id, String title, String destinationName, LocalDate startDate, LocalDate endDate) {
    }

    /** @param onTrip false for a companion who stepped back after the trip locked */
    public record Member(UUID userId, String firstName, Role role, boolean onTrip, boolean me, long netPaise) {
    }

    public record PersonRef(UUID userId, String firstName) {
    }

    /**
     * @param payment for the payer of an open settlement only, and only when
     *                the payee has a UPI ID set; null otherwise
     */
    public record SettlementView(UUID id, PersonRef from, PersonRef to, long amountPaise, SettlementStatus status,
                                 Instant createdAt, Instant markedPaidAt, Instant confirmedAt, Part myPart,
                                 boolean canMarkPaid, boolean canConfirm, boolean canDispute, Payment payment) {
    }

    /** @param upiChangedRecently the payee changed their UPI ID shortly before this; worth a second look */
    public record Payment(String upiLink, String payeeUpiId, boolean upiChangedRecently) {
    }

    public record ExpenseView(UUID id, String description, long amountPaise, PersonRef paidBy, Instant createdAt,
                              List<Share> shares, boolean reversed, boolean canReverse) {
    }

    public record Share(PersonRef person, long sharePaise) {
    }

    /**
     * One line of the ledger's history.
     *
     * @param paidBy       EXPENSE only
     * @param from         SETTLEMENT only: who paid
     * @param to           SETTLEMENT only: who was paid
     * @param reversesId   EXPENSE_REVERSAL only: the expense it corrects
     * @param myEffectPaise how this line moved the caller's balance: up means more is owed to them
     */
    public record HistoryItem(UUID id, LedgerKind kind, Instant createdAt, String description, long amountPaise,
                              PersonRef paidBy, PersonRef from, PersonRef to, UUID reversesId, long myEffectPaise) {
    }

    /** A new expense as the caller described it, already checked for shape. */
    public record NewExpense(UUID clientExpenseId, String description, long amountPaise, SplitMethod split,
                             List<ParticipantShare> participants) {
    }

    /** @param sharePaise EXACT splits only; null for EQUAL */
    public record ParticipantShare(UUID userId, Long sharePaise) {
    }

    public enum SplitMethod {
        EQUAL, EXACT
    }
}
