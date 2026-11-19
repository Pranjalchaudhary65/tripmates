package com.tripmate.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Plays the user's phone: computes RFC 6238 codes from the Base32 secret the
 * setup endpoint returned. Written separately from the production
 * {@code Totp} class, so an integration test that passes means the server
 * agrees with an independent implementation, not with itself.
 *
 * <p>{@link #nextCode()} moves the shared test clock to the next 30-second
 * step first, because the server accepts each step once only; a test that
 * calls it must reset the clock afterwards.
 */
public final class TestAuthenticator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final Duration STEP = Duration.ofSeconds(30);

    private final byte[] secret;

    private final MutableClock clock;

    public TestAuthenticator(String base32Secret, MutableClock clock) {
        this.secret = decodeBase32(base32Secret);
        this.clock = clock;
    }

    /** The code for the current step, without moving the clock. */
    public String currentCode() {
        return codeAt(secret, clock.instant());
    }

    /** Moves to a step the server has not seen yet, then returns its code. */
    public String nextCode() {
        clock.advance(STEP);
        return currentCode();
    }

    /** A six-digit code that is not the right one for any step in the window around now. */
    public String wrongCode() {
        Instant now = clock.instant();
        for (int candidate = 0; ; candidate++) {
            String code = String.format("%06d", candidate);
            if (!code.equals(codeAt(secret, now.minus(STEP))) && !code.equals(codeAt(secret, now))
                    && !code.equals(codeAt(secret, now.plus(STEP)))) {
                return code;
            }
        }
    }

    public byte[] secret() {
        return secret.clone();
    }

    public static String codeAt(byte[] secret, Instant at) {
        long counter = at.getEpochSecond() / STEP.toSeconds();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int o = h[19] & 0xf;
            int value = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", value % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] decodeBase32(String encoded) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : encoded.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not Base32: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
