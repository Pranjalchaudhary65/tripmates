package com.tripmate.platform.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import java.util.regex.Pattern;

/**
 * Masks email addresses and phone numbers in every string field of every
 * structured log line, the message and stack traces included. The code
 * never logs either on purpose; this is the net for the time it does by
 * accident, say inside a library's exception message. Registered through
 * {@code logging.structured.json.customizer}.
 *
 * <p>An email keeps its first character and domain ({@code a***@example.com})
 * and a phone number its last two digits, enough to tell two log lines
 * apart and not enough to contact anyone. UUIDs, timestamps and correlation
 * IDs are left alone: the phone pattern needs 10 to 16 digits in a row,
 * optionally after +, with spaces or dashes between groups only.
 */
public class PiiMaskingLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    static final Pattern PHONE = Pattern.compile("(?<![\\w-])\\+?\\d(?:[ -]?\\d){7,13}(\\d)(\\d)(?![\\w-])");

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, PiiMaskingLogCustomizer::mask));
    }

    static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = EMAIL.matcher(value).replaceAll("$1***@$2");
        return PHONE.matcher(masked).replaceAll("**********$1$2");
    }
}
