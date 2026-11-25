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
    UPI_ID("upi_ids.vpa_ciphertext"),

    /** A traveller's own note for their emergency contact (where they are staying, who to ask for). */
    ITINERARY_NOTE("itinerary_shares.note_ciphertext"),

    /** The exact point of a trip check-in; only a rounded one is stored in the clear. */
    CHECKIN_LOCATION("checkins.location_ciphertext"),

    /** The exact point an SOS was raised from. */
    SOS_LOCATION("sos_events.last_known_location_ciphertext"),

    /** What a reporter wrote about the person they reported; read only by a moderator. */
    REPORT_DETAILS("reports.details_ciphertext");

    private final String column;

    EncryptedField(String column) {
        this.column = column;
    }

    String column() {
        return column;
    }
}
