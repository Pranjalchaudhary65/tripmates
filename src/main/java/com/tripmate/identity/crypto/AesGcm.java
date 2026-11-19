package com.tripmate.identity.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM with a fresh random 96-bit nonce per call, written as
 * {@code nonce | ciphertext | tag}. GCM authenticates as well as encrypts:
 * a flipped bit, a truncated value, or a value moved to a row or column whose
 * additional data differs, fails to open instead of decrypting to garbage.
 *
 * <p>Random nonces are safe for this use: the 2^-32 collision bound is
 * reached only after about four billion encryptions under one key, and each
 * user's key encrypts a handful of fields.
 */
final class AesGcm {

    static final int NONCE_BYTES = 12;

    static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    static byte[] seal(byte[] key, byte[] plaintext, String additionalData) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(additionalData.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] out = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM is a mandatory JDK algorithm", e);
        }
    }

    /**
     * @throws DecryptionFailedException when the value was altered, belongs to
     *         other additional data, or was sealed under another key
     */
    static byte[] open(byte[] key, byte[] sealed, String additionalData) {
        if (sealed.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new DecryptionFailedException("Ciphertext is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 0, NONCE_BYTES)));
            cipher.updateAAD(additionalData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (AEADBadTagException e) {
            throw new DecryptionFailedException("Ciphertext failed authentication");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM is a mandatory JDK algorithm", e);
        }
    }
}
