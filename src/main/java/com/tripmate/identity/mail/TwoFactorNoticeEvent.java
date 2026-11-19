package com.tripmate.identity.mail;

import java.time.Instant;

/**
 * A change to the account's second factor that its owner must hear about,
 * whoever made it. Published inside the transaction that made the change, so
 * the email goes out only once it is committed.
 *
 * @param backupCodesLeft unused backup codes after the change; only meaningful for {@link Kind#BACKUP_CODE_USED}
 */
public record TwoFactorNoticeEvent(String email, String fullName, Kind kind, Instant at, String ipAddress,
                                   int backupCodesLeft) {

    public enum Kind {
        ENABLED, DISABLED, BACKUP_CODES_REGENERATED, BACKUP_CODE_USED
    }

    @Override
    public String toString() {
        return "TwoFactorNoticeEvent[kind=" + kind + ", at=" + at + "]";
    }
}
