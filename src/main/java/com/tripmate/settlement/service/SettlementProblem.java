package com.tripmate.settlement.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from the shared ledger and settle-up. Every detail is a fixed sentence. */
public enum SettlementProblem implements ProblemType {

    LEDGER_NOT_OPEN(HttpStatus.CONFLICT, "ledger_not_open",
            "Expenses can be shared once the trip is locked, after a companion has met the host in person.", null),

    NOT_ON_TRIP(HttpStatus.CONFLICT, "not_on_trip", "Only people still on the trip can add expenses.", null),

    INVALID_DESCRIPTION(HttpStatus.BAD_REQUEST, "invalid_description",
            "Describe the expense in 1 to 120 characters.", "description"),

    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "invalid_amount",
            "Enter an amount in whole paise, more than zero and at most 10,00,000 rupees.", "amountPaise"),

    PARTICIPANT_NOT_ON_TRIP(HttpStatus.BAD_REQUEST, "participant_not_on_trip",
            "Everyone sharing an expense must be on the trip.", "participants"),

    INVALID_SPLIT(HttpStatus.BAD_REQUEST, "invalid_split",
            "Each share must be more than zero and the shares must add up to the amount exactly.", "participants"),

    SPLIT_NEEDS_SOMEONE_ELSE(HttpStatus.BAD_REQUEST, "split_needs_someone_else",
            "Share the expense with at least one other person on the trip.", "participants"),

    EXPENSE_ID_REUSED(HttpStatus.CONFLICT, "expense_id_reused",
            "This expense was already recorded with different details. Start a new one.", null),

    NOT_YOUR_EXPENSE(HttpStatus.CONFLICT, "not_your_expense",
            "Only the person who recorded an expense can reverse it.", null),

    NOT_AN_EXPENSE(HttpStatus.CONFLICT, "not_an_expense", "Only an expense can be reversed.", null),

    NOT_THE_PAYER(HttpStatus.CONFLICT, "not_the_payer", "Only the person paying can mark this as sent.", null),

    NOT_THE_PAYEE(HttpStatus.CONFLICT, "not_the_payee",
            "Only the person being paid can confirm they received it.", null),

    OWN_PAYMENT(HttpStatus.CONFLICT, "cannot_confirm_own_payment",
            "The person you paid confirms it. You cannot confirm your own payment.", null),

    NOT_MARKED_PAID(HttpStatus.CONFLICT, "not_marked_paid",
            "This payment has not been marked as sent yet.", null),

    NOT_A_PARTY(HttpStatus.CONFLICT, "not_a_party",
            "Only the two people in this payment can dispute it.", null),

    SETTLEMENT_CLOSED(HttpStatus.CONFLICT, "settlement_closed",
            "This payment is no longer open. The settle-up list shows what is still owed.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    SettlementProblem(HttpStatus status, String code, String detail, String field) {
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.field = field;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public String field() {
        return field;
    }
}
