package com.tripmate.identity.service;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * HMAC-SHA256 of a normalised backup code, keyed with OTP_HMAC_KEY. The
 * {@code totp-backup|} prefix separates these MACs from the email codes
 * hashed under the same key, and the user ID ties each hash to its owner.
 *
 * <p>Keyed rather than plain SHA-256: 50 bits is beyond enumerating per
 * user once a server-side key is needed, but an unkeyed hash of 50 bits is
 * within reach of a GPU farm with a stolen table. Keyed rather than
 * Argon2id: a login attempt checks against up to ten codes, and ten Argon2id
 * verifications per attempt would be half a second of CPU an attacker gets
 * to spend for free.
 */
@Component
public class BackupCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public BackupCodeHasher(VerificationProperties properties) {
        this.key = new SecretKeySpec(properties.hmacKeyBytes(), ALGORITHM);
    }

    String hash(UUID userId, String normalisedCode) {
        return HexFormat.of().formatHex(mac(userId, normalisedCode));
    }

    /** Constant-time, like every other secret comparison in this module. */
    boolean matches(UUID userId, String normalisedCode, String storedHash) {
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(storedHash);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(expected, mac(userId, normalisedCode));
    }

    private byte[] mac(UUID userId, String normalisedCode) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(("totp-backup|" + userId + "|" + normalisedCode).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is a mandatory JDK algorithm", e);
        }
    }
}
