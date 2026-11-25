package com.tripmate.trust.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from reviews. Every detail is a fixed sentence. */
public enum ReviewProblem implements ProblemType {

    INVALID_RATING(HttpStatus.BAD_REQUEST, "invalid_rating", "Choose a rating from 1 to 5 stars.", "rating"),

    INVALID_COMMENT(HttpStatus.BAD_REQUEST, "invalid_comment",
            "Keep the comment to 500 characters of plain text.", "comment"),

    NOT_A_TRAVEL_COMPANION(HttpStatus.BAD_REQUEST, "not_a_travel_companion",
            "You can only review someone who travelled on this trip with you.", "revieweeId"),

    TRIP_NOT_REVIEWABLE(HttpStatus.CONFLICT, "trip_not_reviewable",
            "This trip was cancelled or never locked, so there is nobody to review.", null),

    REVIEW_WINDOW_NOT_OPEN(HttpStatus.CONFLICT, "review_window_not_open",
            "Reviews open the day after the trip ends.", null),

    REVIEW_WINDOW_CLOSED(HttpStatus.CONFLICT, "review_window_closed",
            "Reviews for this trip have closed.", null),

    ALREADY_REVIEWED(HttpStatus.CONFLICT, "already_reviewed",
            "You have already reviewed this person for this trip. Reviews cannot be changed.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    ReviewProblem(HttpStatus status, String code, String detail, String field) {
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
