package com.tripmate.identity.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Generation and normalisation of 2FA backup codes.
 *
 * <p>Ten characters from Crockford's Base32 alphabet, 5 bits each: 50 random
 * bits per code, shown as {@code XXXXX-XXXXX}. Crockford's alphabet leaves
 * out I, L, O and U, so a code read off paper cannot be mistyped as a
 * look-alike: on input, O is read as 0 and I or L as 1, and case, spaces and
 * the hyphen are ignored.
 */
final class BackupCodes {

    static final int PER_SET = 10;

    static final int LENGTH = 10;

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final Pattern NORMALISED = Pattern.compile("[0-9A-HJKMNP-TV-Z]{" + LENGTH + "}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCodes() {
    }

    /** A new set, in display form. */
    static List<String> generate() {
        List<String> codes = new ArrayList<>(PER_SET);
        for (int i = 0; i < PER_SET; i++) {
            StringBuilder code = new StringBuilder(LENGTH + 1);
            for (int c = 0; c < LENGTH; c++) {
                if (c == LENGTH / 2) {
                    code.append('-');
                }
                code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            codes.add(code.toString());
        }
        return codes;
    }

    /** The canonical form that is hashed, or empty when the input cannot be a backup code at all. */
    static Optional<String> normalise(String candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        String normalised = candidate.toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "")
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1');
        return NORMALISED.matcher(normalised).matches() ? Optional.of(normalised) : Optional.empty();
    }
}
