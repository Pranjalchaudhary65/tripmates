package com.tripmate.chat.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes contact details from a message before it is stored, so the masked
 * chat stays masked whatever the client does: there is no unmasking to
 * reverse later, because the details were never written down. Real contact
 * details are shared only through the separate contact endpoint once the
 * trip locks.
 *
 * <p>What counts as a contact detail, in the order checked:
 * <ol>
 *   <li>email addresses;</li>
 *   <li>UPI IDs ({@code name@bank}), which double as payment handles;</li>
 *   <li>links with a scheme or {@code www.}, and messenger or social links
 *       with or without one ({@code wa.me/...}, {@code t.me/...},
 *       {@code instagram.com/...});</li>
 *   <li>{@code @handles};</li>
 *   <li>phone numbers: ten or more digits, allowing spaces, dots, dashes,
 *       brackets and a leading {@code +} between them. Fewer than ten is
 *       left alone, so dates, prices and times are not mangled.</li>
 * </ol>
 *
 * <p>This is a floor, not a guarantee: a determined person can spell digits
 * out in words. That case is left to the moderation flag on off-platform
 * pushes and to the trust ladder, which never needs the number anyway.
 */
public final class ContactRedactor {

    public static final String PLACEHOLDER = "[contact hidden]";

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}"),
            Pattern.compile("(?<![\\p{L}\\p{N}._-])[\\p{L}\\p{N}._-]{2,}@[\\p{L}]{2,}(?![\\p{L}\\p{N}.@])"),
            Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+"),
            Pattern.compile("(?i)\\b(?:wa\\.me|api\\.whatsapp\\.com|chat\\.whatsapp\\.com|t\\.me|telegram\\.me"
                    + "|instagram\\.com|instagr\\.am|facebook\\.com|fb\\.com|fb\\.me|m\\.me|snapchat\\.com"
                    + "|linkedin\\.com|twitter\\.com|x\\.com)/\\S*"),
            Pattern.compile("(?<![\\p{L}\\p{N}._@])@[\\p{L}\\p{N}_.]{3,30}"),
            Pattern.compile("(?<![\\p{N}])\\+?\\p{N}(?:[\\s.\\-()]{0,3}\\p{N}){9,}(?![\\p{N}])"));

    private ContactRedactor() {
    }

    public static Redaction redact(String text) {
        String result = text;
        boolean redacted = false;
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                redacted = true;
                result = matcher.replaceAll(Matcher.quoteReplacement(PLACEHOLDER));
            }
        }
        return new Redaction(result, redacted);
    }

    /** @param redacted whether anything was removed */
    public record Redaction(String text, boolean redacted) {
    }
}
