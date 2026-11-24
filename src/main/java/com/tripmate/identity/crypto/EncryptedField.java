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
    CHAT_MESSAGE("chat_messages.body_ciphertext");

    private final String column;

    EncryptedField(String column) {
        this.column = column;
    }

    String column() {
        return column;
    }
}
