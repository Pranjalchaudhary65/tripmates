package com.tripmate.vibe.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Quiz answers by question ID. A draft may leave questions out; a submission answers all of them. */
public record QuizAnswersRequest(

        @Schema(description = "Question ID to option ID, as GET /vibe/quiz lists them",
                example = "{\"budget-stay\":\"b\",\"social-evenings\":\"4\"}")
        @NotNull @Size(max = 32)
        Map<String, String> answers) {
}
