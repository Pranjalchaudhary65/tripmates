package com.tripmate.platform.error;

import org.springframework.http.HttpStatus;

/** Errors any module can answer with. */
public enum CommonProblem implements ProblemType {

    /**
     * Nothing exists at this ID, or it exists and the caller may not see it:
     * one answer for both, so an ID tried by someone who does not own it
     * reveals nothing, not even that it is in use.
     */
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found", "The requested resource was not found.");

    private final HttpStatus status;

    private final String code;

    private final String detail;

    CommonProblem(HttpStatus status, String code, String detail) {
        this.status = status;
        this.code = code;
        this.detail = detail;
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
        return null;
    }
}
