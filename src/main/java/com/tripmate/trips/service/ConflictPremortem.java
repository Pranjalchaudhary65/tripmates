package com.tripmate.trips.service;

import com.tripmate.trips.service.MatchScorer.Dimension;
import com.tripmate.vibe.entity.VibeDimensions;

import java.util.List;
import java.util.Objects;

/**
 * Before a locked trip starts: where two travellers' vibes are far enough
 * apart to cause friction on the road, each with a question worth settling
 * before they go. Pluggable like the match scorer; the rule-based default
 * needs no API key. It never says one person is wrong, and never shows a raw
 * score or quiz answer.
 */
public interface ConflictPremortem {

    /** The value of tripmate.trips.premortem that selects this implementation. */
    String id();

    /**
     * @param mine       the caller's dimensions
     * @param theirs     the other traveller's
     * @param theirName  their first name, for the wording
     */
    List<Topic> topics(VibeDimensions mine, VibeDimensions theirs, String theirName);

    /**
     * @param gap      what differs, in one fixed sentence
     * @param question what to agree on before the trip
     */
    record Topic(Dimension dimension, String gap, String question) {

        public Topic {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(gap, "gap");
            Objects.requireNonNull(question, "question");
        }
    }
}
