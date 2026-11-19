package com.tripmate.identity.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * RFC 6238 time-based one-time passwords, on the JDK's {@code Mac}. The
 * parameters are the ones every authenticator app assumes when an otpauth
 * URI does not say otherwise, and the URI says them anyway: HMAC-SHA1,
 * 6 digits, 30-second steps counted from the Unix epoch.
 *
 * <p>SHA-1 is not a weakness here. HOTP needs HMAC to be a PRF, which
 * HMAC-SHA1 still is; SHA-1's collision attacks do not touch it. SHA-256
 * would be allowed by the RFC, but several widely used apps ignore the
 * algorithm parameter and silently compute SHA-1 codes, which would lock
 * users out.
 */
public final class Totp {

    public static final String ALGORITHM = "SHA1";

    public static final int DIGITS = 6;

    public static final int PERIOD_SECONDS = 30;

    /** 160 bits, the secret length RFC 4226 recommends. */
    static final int SECRET_BYTES = 20;

    /** Steps either side of now that are still accepted: absorbs phone clock drift and typing time. */
    static final int WINDOW = 1;

    private static final String MAC_ALGORITHM = "HmacSHA1";

    private static final int MODULUS = 1_000_000;

    private static final Pattern CODE = Pattern.compile("\\d{" + DIGITS + "}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    static byte[] generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    static boolean isWellFormed(String candidate) {
        return candidate != null && CODE.matcher(candidate).matches();
    }

    static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), PERIOD_SECONDS);
    }

    /** RFC 4226 HOTP for one counter value: HMAC, dynamic truncation, the low six decimal digits. */
    static String codeAt(byte[] secret, long step) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, MAC_ALGORITHM));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is a mandatory JDK algorithm", e);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format("%0" + DIGITS + "d", binary % MODULUS);
    }

    /**
     * The step {@code candidate} is valid for, within the window around
     * {@code now}, provided it is later than {@code lastUsedStep}. Every step
     * in the window is computed and compared in constant time, whatever
     * matched first, so timing reveals neither how close a guess was nor
     * which step it fell in.
     *
     * @param lastUsedStep the newest step already accepted, or null if none
     */
    static OptionalLong verify(byte[] secret, String candidate, Instant now, Long lastUsedStep) {
        if (!isWellFormed(candidate)) {
            return OptionalLong.empty();
        }
        byte[] presented = candidate.getBytes(StandardCharsets.US_ASCII);
        long current = stepAt(now);
        long floor = lastUsedStep == null ? Long.MIN_VALUE : lastUsedStep;
        long matched = Long.MIN_VALUE;
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            boolean equal = MessageDigest.isEqual(codeAt(secret, step).getBytes(StandardCharsets.US_ASCII), presented);
            if (equal && step > floor && step > matched) {
                matched = step;
            }
        }
        return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
    }
}
