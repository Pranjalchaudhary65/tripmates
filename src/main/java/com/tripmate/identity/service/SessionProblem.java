package com.tripmate.identity.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

public enum SessionProblem implements ProblemType {

    /**
     * No cookie, a malformed one, an unknown, expired or revoked family, or a
     * reused token: one answer for all of them, so a caller probing with
     * made-up or stolen values learns nothing about which case it hit.
     */
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
            "Your session has ended. Log in again.");

    private final HttpStatus status;

    private final String code;

    private final String detail;

    SessionProblem(HttpStatus status, String code, String detail) {
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
