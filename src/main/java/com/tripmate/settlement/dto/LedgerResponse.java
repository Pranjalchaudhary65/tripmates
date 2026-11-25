package com.tripmate.settlement.dto;

import com.tripmate.settlement.entity.SettlementStatus;
import com.tripmate.settlement.service.SettlementViews.Part;
import com.tripmate.settlement.service.SettlementViews.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A trip's settle-up screen. Amounts are whole paise. A balance is what the
 * others owe that person (positive) or what they owe (negative): obligations
 * between travellers, never money held by TripMates.
 *
 * @param locked     the trip is locked: the host has at least one companion who met them in person
 * @param open       the caller can add expenses: locked, and still on the trip
 * @param settledUp  nobody owes anything and no payment is waiting
 */
public record LedgerResponse(Trip trip, boolean locked, boolean open, @Schema(example = "INR") String currency,
                             List<Member> members, long myNetPaise, long totalSpentPaise,
                             List<Settlement> settlements, List<Expense> recentExpenses, boolean settledUp) {

    public record Trip(UUID id, String title, String destinationName, LocalDate startDate, LocalDate endDate) {
    }

    public record Member(UUID userId, String firstName, Role role, boolean onTrip, boolean me, long netPaise) {
    }

    public record Person(UUID userId, String firstName) {
    }

    /** @param payment the payer's UPI link; null for anyone else, or when the payee has no UPI ID set */
    public record Settlement(UUID id, Person from, Person to, long amountPaise, SettlementStatus status,
                             Instant createdAt, Instant markedPaidAt, Instant confirmedAt, Part myPart,
                             boolean canMarkPaid, boolean canConfirm, boolean canDispute, Payment payment) {
    }

    public record Payment(@Schema(example = "upi://pay?pa=asha@okbank&pn=Asha&am=1200.00&cu=INR&tn=TripMates%20settle-up") String upiLink,
                          String payeeUpiId, boolean upiChangedRecently) {
    }

    public record Expense(UUID id, String description, long amountPaise, Person paidBy, Instant createdAt,
                          List<Share> shares, boolean reversed, boolean canReverse) {
    }

    public record Share(Person person, long sharePaise) {
    }
}
