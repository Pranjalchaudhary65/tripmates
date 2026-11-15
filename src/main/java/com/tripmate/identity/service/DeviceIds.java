package com.tripmate.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The random ID a browser keeps in its device cookie after a successful
 * login. It identifies a browser, not a person, and is stored only as a
 * SHA-256 hash. An unkeyed hash is enough here, unlike for six-digit OTPs: the
 * input is 256 random bits, so there is no small set of candidates to hash
 * and compare.
 */
public final class DeviceIds {

    private static final int ID_BYTES = 32;

    /** 32 bytes as unpadded base64url. */
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceIds() {
    }

    public static String generate() {
        byte[] id = new byte[ID_BYTES];
        RANDOM.nextBytes(id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id);
    }

    /** A cookie value that could have come from {@link #generate()}; anything else is treated as no cookie. */
    public static boolean isWellFormed(String candidate) {
        return candidate != null && WELL_FORMED.matcher(candidate).matches();
    }

    public static String hash(String deviceId) {
        return sha256Hex(deviceId);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
