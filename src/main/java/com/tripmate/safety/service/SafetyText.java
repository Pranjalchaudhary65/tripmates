package com.tripmate.safety.service;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The traveller's note for their emergency contact, as stored: NFC, control
 * and direction-override characters removed, whitespace collapsed, at most
 * 300 characters. It goes into an SMS word for word, so nothing in it may
 * reorder or hide the rest of the alert.
 */
public final class SafetyText {

    public static final int MAX_NOTE_LENGTH = 300;

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SafetyText() {
    }

    /** Empty for no note (null or nothing visible); refused when longer than allowed. */
    public static Optional<String> cleanNote(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = INVISIBLE.matcher(text).replaceAll("").strip();
        if (text.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Note too long");
        }
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
