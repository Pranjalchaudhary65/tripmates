package com.tripmate.chat.service;

import com.tripmate.vibe.entity.BudgetBand;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Suggests opening lines for a conversation. Pluggable: the rule-based
 * default needs no API key, and a model-backed generator can replace it
 * behind {@code tripmate.chat.icebreakers}.
 *
 * <p>Whatever an implementation returns is treated as untrusted text: the
 * caller cleans, redacts and length-limits every suggestion, and drops any
 * that come back empty or carrying contact details. A suggestion is only
 * ever shown to the person asking; it becomes a message only if they send
 * it themselves, as their own words.
 *
 * <p>The context holds trip facts and a first name, nothing else: no user
 * IDs, no profile answers, nothing a generator could leak or should keep.
 */
public interface IcebreakerGenerator {

    /** The value of {@code tripmate.chat.icebreakers} that selects this implementation. */
    String id();

    List<String> suggest(Context context);

    /**
     * @param conversationId lets a generator keep its choice stable for one
     *                       conversation, so suggestions do not reshuffle on every load
     * @param viewerIsHost   whether the person asking hosts the trip
     */
    record Context(UUID conversationId, String destinationName, LocalDate startDate, LocalDate endDate,
                   BudgetBand budgetBand, boolean viewerIsHost, String otherFirstName) {
    }
}
