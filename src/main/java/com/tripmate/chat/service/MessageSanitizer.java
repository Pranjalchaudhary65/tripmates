package com.tripmate.chat.service;

import org.owasp.html.HtmlSanitizer;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;

/**
 * Turns whatever a client sent as a chat message into plain text: the words
 * a person would read, and nothing a renderer could act on.
 *
 * <ul>
 *   <li>Markup is parsed by the OWASP sanitizer's HTML lexer, not a regex,
 *       and every element is dropped. Only text nodes survive, already
 *       entity-decoded, and the content of script, style and similar
 *       elements is dropped with them. So {@code <b>hi</b>} becomes
 *       {@code hi}, while {@code I <3 Goa} is text and stays.</li>
 *   <li>Characters that change how text looks without being text are
 *       removed: control characters other than newline, direction overrides
 *       and isolates (which can make a message read backwards, the trick
 *       behind spoofed file names and links), zero-width spaces and the
 *       byte-order mark. Tabs become spaces.</li>
 *   <li>Unicode is normalised to NFC, so the same visible word is the same
 *       string for redaction and moderation.</li>
 *   <li>Trailing spaces go, runs of blank lines shrink to one, and the whole
 *       message is trimmed.</li>
 * </ul>
 *
 * <p>The client renders messages as text whatever the server sends; this is
 * the server's half, so a message is safe in every other place it may end
 * up later (an email notification, the moderation panel).
 */
public final class MessageSanitizer {

    /** Elements whose text content is not something a person wrote to be read. */
    private static final Set<String> DROPPED_CONTENT = Set.of(
            "script", "style", "template", "noscript", "iframe", "object", "embed", "noembed", "noframes",
            "xmp", "plaintext", "title", "textarea", "select", "svg", "math");

    /** Elements that break a line where they stand. */
    private static final Set<String> LINE_BREAKS = Set.of("br", "p", "div", "li", "tr", "h1", "h2", "h3", "h4",
            "h5", "h6", "blockquote", "pre");

    private MessageSanitizer() {
    }

    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        text = stripMarkup(text);
        text = stripInvisible(Normalizer.normalize(text, Normalizer.Form.NFC));
        return tidyWhitespace(text);
    }

    private static String stripMarkup(String text) {
        StringBuilder out = new StringBuilder(text.length());
        HtmlSanitizer.sanitize(text, new HtmlSanitizer.Policy() {

            private int droppedDepth;

            @Override
            public void openDocument() {
            }

            @Override
            public void closeDocument() {
            }

            @Override
            public void openTag(String elementName, List<String> attributes) {
                if (DROPPED_CONTENT.contains(elementName)) {
                    droppedDepth++;
                } else if (droppedDepth == 0 && LINE_BREAKS.contains(elementName)) {
                    out.append('\n');
                }
            }

            @Override
            public void closeTag(String elementName) {
                if (DROPPED_CONTENT.contains(elementName) && droppedDepth > 0) {
                    droppedDepth--;
                }
            }

            @Override
            public void text(String textChunk) {
                if (droppedDepth == 0) {
                    out.append(textChunk);
                }
            }
        });
        return out.toString();
    }

    private static String stripInvisible(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '\n') {
                out.append('\n');
            } else if (codePoint == '\t') {
                out.append(' ');
            } else if (codePoint == 0x2028 || codePoint == 0x2029) {
                out.append('\n');
            } else if (!isInvisible(codePoint)) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.CONTROL || type == Character.UNASSIGNED || type == Character.PRIVATE_USE
                || type == Character.SURROGATE) {
            return true;
        }
        // Zero-width (non-)joiners (U+200C, U+200D) stay: emoji sequences and
        // Devanagari conjuncts are built with them.
        return codePoint == 0x200B                              // zero-width space
                || codePoint == 0x200E || codePoint == 0x200F   // LRM, RLM
                || (codePoint >= 0x202A && codePoint <= 0x202E) // embeddings and overrides
                || (codePoint >= 0x2066 && codePoint <= 0x2069) // isolates
                || codePoint == 0x061C                          // Arabic letter mark
                || codePoint == 0x2060                          // word joiner
                || codePoint == 0xFEFF;                         // byte-order mark
    }

    private static String tidyWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int blankRun = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            if (trimmed.isBlank()) {
                blankRun++;
                if (blankRun > 1) {
                    continue;
                }
                trimmed = "";
            } else {
                blankRun = 0;
            }
            out.append(trimmed).append('\n');
        }
        return out.toString().strip();
    }
}
