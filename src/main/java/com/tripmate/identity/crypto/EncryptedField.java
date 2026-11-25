package com.tripmate.identity.crypto;

/**
 * Every column encrypted under a user's data key. The name goes into the
 * additional data of each ciphertext, so a value can only ever be opened as
 * the column it was written for.
 */
public enum EncryptedField {

    TOTP_SECRET("users.totp_secret_ciphertext"),

    PHONE("users.phone_ciphertext"),

    /** Sealed under the sender's key, so erasing the sender erases what they wrote. */
    CHAT_MESSAGE("chat_messages.body_ciphertext"),

    EMERGENCY_CONTACT_PHONE("emergency_contacts.phone_ciphertext"),

    /** A private note a user keeps about a meet-up, sealed under their own key. */
    MEETUP_PRIVATE_NOTE("meetup.private_note"),

    /** The UPI ID a user wants to be paid at; often their phone number with a bank suffix. */
    UPI_ID("upi_ids.vpa_ciphertext");

    private final String column;

    EncryptedField(String column) {
        this.column = column;
    }

    String column() {
        return column;
    }
}
