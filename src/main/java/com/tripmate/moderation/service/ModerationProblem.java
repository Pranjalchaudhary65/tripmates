package com.tripmate.moderation.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from blocking and reporting. Every detail is a fixed sentence. */
public enum ModerationProblem implements ProblemType {

    INVALID_TARGET(HttpStatus.BAD_REQUEST, "invalid_target",
            "Say who this is about: a match, or a trip and someone on it.", null),

    INVALID_REASON(HttpStatus.BAD_REQUEST, "invalid_reason", "Choose a reason from the list.", "reason"),

    INVALID_DETAILS(HttpStatus.BAD_REQUEST, "invalid_details",
            "Keep the details to 1000 characters of plain text.", "details"),

    ALREADY_REPORTED(HttpStatus.CONFLICT, "already_reported",
            "You have already reported this person. A moderator will look at it.", null),

    INVALID_DECISION(HttpStatus.BAD_REQUEST, "invalid_decision", "Choose ACTIONED or DISMISSED.", "decision"),

    REPORT_CLOSED(HttpStatus.CONFLICT, "report_closed", "This report has already been closed.", null),

    CANNOT_MODERATE_SELF(HttpStatus.CONFLICT, "cannot_moderate_self",
            "You cannot decide about a report you are part of, or about your own account.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    ModerationProblem(HttpStatus status, String code, String detail, String field) {
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
