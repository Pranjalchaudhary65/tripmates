package com.tripmate.moderation.service;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Report details as stored: NFC, invisible and direction-override characters
 * removed, runs of whitespace collapsed, at most 1000 characters. A moderator
 * reads exactly what a reporter could see they typed.
 */
public final class ModerationText {

    public static final int MAX_LENGTH = 1000;

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ModerationText() {
    }

    /** Empty for nothing visible; throws for text that is too long. */
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
            throw new IllegalArgumentException("Details longer than " + MAX_LENGTH);
        }
        return Optional.of(text);
    }
}
