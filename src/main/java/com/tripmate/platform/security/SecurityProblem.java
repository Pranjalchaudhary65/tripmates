package com.tripmate.platform.security;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

public enum SecurityProblem implements ProblemType {

    /** A cookie-authenticated request arrived without the anti-CSRF header. */
    CSRF_REJECTED("csrf_rejected", "This request could not be verified as coming from the TripMates app.");

    private final String code;

    private final String detail;

    SecurityProblem(String code, String detail) {
        this.code = code;
        this.detail = detail;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.FORBIDDEN;
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
