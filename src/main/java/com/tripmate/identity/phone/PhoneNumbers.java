package com.tripmate.identity.phone;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalises the numbers people type into E.164 ({@code +919876543210}), the
 * one form that is encrypted, blind-indexed and compared. Two spellings of
 * the same number must give the same index, or "one account per number"
 * could be dodged with a space.
 *
 * <p>A ten-digit number starting 6-9 is read as an Indian mobile number,
 * since that is what most users will type; anything else needs its country
 * code.
 */
public final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s().-]");

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");

    private static final Pattern INDIAN_WITH_PREFIX = Pattern.compile("^(?:0|91|0091)([6-9]\\d{9})$");

    private static final String INDIA = "+91";

    private PhoneNumbers() {
    }

    /** @return the number in E.164, or empty when it cannot be one */
    public static Optional<String> normalise(String input) {
        if (input == null || input.length() > 32) {
            return Optional.empty();
        }
        String compact = SEPARATORS.matcher(input.strip()).replaceAll("");
        if (INDIAN_MOBILE.matcher(compact).matches()) {
            return Optional.of(INDIA + compact);
        }
        var prefixed = INDIAN_WITH_PREFIX.matcher(compact);
        if (prefixed.matches()) {
            return Optional.of(INDIA + prefixed.group(1));
        }
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }
        return E164.matcher(compact).matches() ? Optional.of(compact) : Optional.empty();
    }

    /**
     * Enough to recognise your own number, not enough to dial it: the
     * country code for an Indian number, and the last three digits.
     * {@code +919876543210} becomes {@code +91 ••••••• 210}.
     */
    public static String mask(String e164) {
        String lastThree = e164.substring(e164.length() - 3);
        String country = e164.startsWith(INDIA) ? INDIA : "+";
        int hidden = e164.length() - country.length() - 3;
        return country + " " + "•".repeat(Math.max(hidden, 0)) + " " + lastThree;
    }
}
