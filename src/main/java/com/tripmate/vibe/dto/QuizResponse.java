package com.tripmate.vibe.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Everything the quiz screen needs: the questions, and where the caller left off. */
public record QuizResponse(

        @Schema(description = "Answers are only valid against this version of the questions", example = "1")
        int version,

        List<Question> questions,

        @Schema(description = "Answers saved so far; null when there is nothing unfinished")
        Draft draft,

        @Schema(description = "The quiz has been finished before, so submitting again replaces the profile")
        boolean hasProfile) {

    public record Question(String id, String text, List<Option> options) {
    }

    public record Option(String id, String label) {
    }

    public record Draft(Map<String, String> answers, Instant updatedAt) {
    }
}
