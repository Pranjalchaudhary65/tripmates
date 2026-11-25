package com.tripmate.trust.service;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A review comment as stored: NFC, invisible and direction-override
 * characters removed, runs of whitespace collapsed to one space, at most 500
 * characters. A hidden right-to-left override could otherwise make the
 * person reviewed read something other than what was written.
 */
public final class ReviewText {

    public static final int MAX_LENGTH = 500;

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ReviewText() {
    }

    /**
     * Empty for no comment (null, or nothing visible left). Throws for one
     * that is too long, which is the writer's to shorten, not to lose.
     */
    public static Optional<String> clean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = INVISIBLE.matcher(text).replaceAll("").strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Comment longer than " + MAX_LENGTH);
        }
        return Optional.of(text);
    }
}
