package com.tripmate.identity.service;

import com.tripmate.identity.entity.OtpPurpose;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates six-digit codes and hashes them for storage with HMAC-SHA256
 * under a server-side key.
 *
 * <p>A plain hash would be pointless here: there are only a million six-digit
 * codes, so anyone holding a copy of otp_codes could hash all of them in well
 * under a second and read every live code. Keyed hashing means the table
 * alone is useless without the key, which lives in the environment, not the
 * database. Argon2 would not help: a million guesses at Argon2 cost is still
 * hours, not years, and it would make every verify request expensive.
 *
 * <p>The user ID and purpose are part of the MAC input, so a hash copied
 * onto another user's row, or used for a different purpose, never matches.
 */
@Component
public class OtpCodeHasher {

    static final int CODE_DIGITS = 6;

    private static final int CODE_SPACE = 1_000_000;

    private static final String ALGORITHM = "HmacSHA256";

    private final SecureRandom random = new SecureRandom();

    private final SecretKeySpec key;

    public OtpCodeHasher(VerificationProperties properties) {
        this.key = new SecretKeySpec(properties.hmacKeyBytes(), ALGORITHM);
    }

    public String generateCode() {
        return String.format("%0" + CODE_DIGITS + "d", random.nextInt(CODE_SPACE));
    }

    public String hash(UUID userId, OtpPurpose purpose, String code) {
        return HexFormat.of().formatHex(mac(userId, purpose, code));
    }

    /** Constant-time comparison, so response timing reveals nothing about how close a guess was. */
    public boolean matches(UUID userId, OtpPurpose purpose, String candidate, String storedHash) {
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(storedHash);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(expected, mac(userId, purpose, candidate));
    }

    private byte[] mac(UUID userId, OtpPurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            String input = userId + "|" + purpose.name() + "|" + code;
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is a mandatory JDK algorithm", e);
        }
    }
}
