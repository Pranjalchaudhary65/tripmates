package com.tripmate.identity.token;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One HMAC-SHA256 key and the {@code kid} that names it in a token header.
 *
 * <p>The kid is derived from the key (the first 8 bytes of its SHA-256), not
 * configured next to it, so a key and its ID can never be paired wrongly by
 * a typo in the environment. 64 bits of a hash of a 256-bit random key say
 * nothing usable about the key.
 */
public final class SigningKey {

    /** RFC 7518 §3.2: an HS256 key must be at least as long as the hash output. */
    static final int MIN_KEY_BYTES = 32;

    private static final int KID_BYTES = 8;

    private final String keyId;

    private final SecretKey secret;

    private SigningKey(byte[] material) {
        this.secret = new SecretKeySpec(material, "HmacSHA256");
        this.keyId = HexFormat.of().formatHex(sha256(material), 0, KID_BYTES);
    }

    /**
     * @param property the setting the value came from, named in the error so
     *                 a bad .env entry is easy to find; the value itself never is
     */
    static SigningKey fromBase64(String base64, String property) {
        byte[] material;
        try {
            material = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(property + " is not valid base64");
        }
        if (material.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(property + " must decode to at least " + MIN_KEY_BYTES + " bytes");
        }
        return new SigningKey(material);
    }

    public String keyId() {
        return keyId;
    }

    SecretKey secret() {
        return secret;
    }

    private static byte[] sha256(byte[] material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    @Override
    public String toString() {
        return "SigningKey[kid=" + keyId + "]";
    }
}
