package com.tripmate.platform.ratelimit;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

public enum RateLimitProblem implements ProblemType {

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
            "Too many attempts. Wait for the number of seconds in the Retry-After header, then try again."),

    /** The limit could not be checked, so the request was refused rather than let through unchecked. */
    TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable",
            "This action is temporarily unavailable. Try again shortly.");

    private final HttpStatus status;

    private final String code;

    private final String detail;

    RateLimitProblem(HttpStatus status, String code, String detail) {
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
