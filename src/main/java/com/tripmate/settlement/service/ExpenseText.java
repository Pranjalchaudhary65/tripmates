package com.tripmate.settlement.service;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An expense description as stored: NFC, invisible and direction-override
 * characters removed, runs of whitespace collapsed, 1 to 120 characters.
 * Only trip members see it and the client renders it as text, but a
 * description with a hidden right-to-left override can still make "Hotel 500"
 * read as something else.
 */
public final class ExpenseText {

    public static final int MAX_LENGTH = 120;

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ExpenseText() {
    }

    /** Empty when nothing visible is left, or it is too long. */
    public static Optional<String> clean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = INVISIBLE.matcher(text).replaceAll("").strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_LENGTH || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(text);
    }
}
