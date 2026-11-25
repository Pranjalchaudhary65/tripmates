package com.tripmate.platform.audit;

/** Everything the audit log records. One place, so a reader of the log knows the whole vocabulary. */
public enum AuditEvent {
    /** An admin approved or rejected a selfie check. */
    ADMIN_VERIFICATION_DECIDED,
    /** An account was suspended, by a moderator or by the report threshold (no actor). */
    ACCOUNT_SUSPENDED,
    ACCOUNT_REINSTATED,
    REPORT_FILED,
    /** A moderator closed a report as ACTIONED or DISMISSED. */
    REPORT_CLOSED,
    SOS_RAISED,
    /** A payee confirmed a payment, the one step that writes the ledger. */
    SETTLEMENT_CONFIRMED,
    CONSENT_CHANGED,
    DATA_EXPORT_REQUESTED,
    DATA_EXPORT_COMPLETED,
    DATA_EXPORT_DOWNLOADED,
    ACCOUNT_DELETION_REQUESTED,
    ACCOUNT_DELETED,
    /** An admin ran the integrity check by hand. */
    AUDIT_CHECK_RUN
}
