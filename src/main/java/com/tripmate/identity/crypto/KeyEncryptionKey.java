package com.tripmate.identity.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Wraps and unwraps per-user data keys with the application's KEK.
 *
 * <p>A wrapped key is {@code 0x01 | fingerprint | nonce | ciphertext | tag},
 * the fingerprint being the first 8 bytes of SHA-256 of the KEK. Starting
 * the app with a different KEK then fails with a message that says so,
 * instead of an anonymous authentication failure on the first login, and a
 * later KEK rotation can tell which key wrapped which row. The additional
 * data names the key row and its user, so a wrapped key copied onto another
 * row does not unwrap.
 */
@Component
@EnableConfigurationProperties(DataEncryptionProperties.class)
public class KeyEncryptionKey {

    private static final byte VERSION = 1;

    private static final int FINGERPRINT_BYTES = 8;

    private static final int HEADER_BYTES = 1 + FINGERPRINT_BYTES;

    private final byte[] key;

    private final byte[] fingerprint;

    public KeyEncryptionKey(DataEncryptionProperties properties) {
        this.key = properties.kekBytes();
        this.fingerprint = Arrays.copyOf(sha256(key), FINGERPRINT_BYTES);
    }

    byte[] wrap(byte[] dataKey, UUID keyId, UUID userId) {
        byte[] sealed = AesGcm.seal(key, dataKey, additionalData(keyId, userId));
        byte[] out = new byte[HEADER_BYTES + sealed.length];
        out[0] = VERSION;
        System.arraycopy(fingerprint, 0, out, 1, FINGERPRINT_BYTES);
        System.arraycopy(sealed, 0, out, HEADER_BYTES, sealed.length);
        return out;
    }

    byte[] unwrap(byte[] wrapped, UUID keyId, UUID userId) {
        if (wrapped.length <= HEADER_BYTES || wrapped[0] != VERSION) {
            throw new DecryptionFailedException("Wrapped data key has an unknown format");
        }
        if (!MessageDigest.isEqual(fingerprint, Arrays.copyOfRange(wrapped, 1, HEADER_BYTES))) {
            throw new IllegalStateException("Data key " + keyId + " was wrapped under a different "
                    + "DATA_ENCRYPTION_KEK than the one configured");
        }
        return AesGcm.open(key, Arrays.copyOfRange(wrapped, HEADER_BYTES, wrapped.length),
                additionalData(keyId, userId));
    }

    private static String additionalData(UUID keyId, UUID userId) {
        return "user_encryption_keys|" + keyId + "|" + userId;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    @Override
    public String toString() {
        return "KeyEncryptionKey[****]";
    }
}
