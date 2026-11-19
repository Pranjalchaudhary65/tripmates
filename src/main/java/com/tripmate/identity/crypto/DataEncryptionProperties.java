package com.tripmate.identity.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;

/**
 * The Key Encryption Key that wraps every user's data key. No default: an
 * unset, malformed or wrong-sized key stops startup, because data encrypted
 * under a guessable key is not encrypted.
 *
 * @param kek base64 of exactly 32 random bytes (AES-256)
 */
@ConfigurationProperties("tripmate.identity.encryption")
public record DataEncryptionProperties(String kek) {

    static final int KEY_BYTES = 32;

    public DataEncryptionProperties {
        if (kek == null || kek.isBlank()) {
            throw new IllegalArgumentException("tripmate.identity.encryption.kek (DATA_ENCRYPTION_KEK) is required");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(kek.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DATA_ENCRYPTION_KEK must be base64", e);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("DATA_ENCRYPTION_KEK must decode to exactly " + KEY_BYTES + " bytes");
        }
    }

    byte[] kekBytes() {
        return Base64.getDecoder().decode(kek.trim());
    }

    /** Never print the key, whatever logs a bound properties object. */
    @Override
    public String toString() {
        return "DataEncryptionProperties[kek=****]";
    }
}
