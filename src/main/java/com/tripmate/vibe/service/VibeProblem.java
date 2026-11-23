package com.tripmate.vibe.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from the vibe quiz and profile. All are about the request, none about the session. */
public enum VibeProblem implements ProblemType {

    /** A question ID the quiz does not have, or an option that is not one of its question's. */
    INVALID_QUIZ_ANSWER(HttpStatus.BAD_REQUEST, "invalid_quiz_answer",
            "One of the answers is not an option of its question. Reload the quiz and try again.", "answers"),

    QUIZ_INCOMPLETE(HttpStatus.BAD_REQUEST, "quiz_incomplete",
            "Answer every question before finishing the quiz.", "answers"),

    VIBE_PROFILE_MISSING(HttpStatus.CONFLICT, "vibe_profile_missing",
            "Take the vibe quiz first. You can adjust the result afterwards.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    VibeProblem(HttpStatus status, String code, String detail, String field) {
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.field = field;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public String field() {
        return field;
    }
}
