package com.tripmate.chat.service;

import com.tripmate.chat.entity.FlagType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Decides whether a chat message is something the moderation queue, and the
 * other person, should be warned about. Pluggable: the rule-based default
 * needs no API key; a model-backed one can replace it behind
 * {@code tripmate.chat.classifier} without touching the send path.
 *
 * <p>A classifier only labels. It never changes the text, blocks the
 * message or acts on an account; what happens with a label is decided by
 * {@link MessageService} from the label and its confidence alone.
 */
public interface MessageClassifier {

    /** The value of {@code tripmate.chat.classifier} that selects this implementation. */
    String id();

    /**
     * @param text the cleaned message before contact details were redacted, so
     *             a pasted phone number still counts as an off-platform push.
     *             It is never stored.
     * @param hadContactDetails whether redaction removed anything from it
     * @return every label that applies, strongest first; empty for none
     */
    List<Label> classify(String text, boolean hadContactDetails);

    /** @param confidence 0 to 1, three decimal places */
    record Label(FlagType type, BigDecimal confidence) {
    }
}
