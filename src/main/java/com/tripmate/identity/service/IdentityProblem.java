package com.tripmate.identity.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/**
 * Every identity error a client can receive. None of them depends on
 * whether an email address is registered: each is decided from the request
 * alone, or (for the code) is the same answer for every way verification can
 * fail.
 */
public enum IdentityProblem implements ProblemType {

    UNDERAGE("underage",
            "You must be at least 18 years old to use TripMates.", "dateOfBirth"),

    PASSWORD_BREACHED("password_breached",
            "This password has appeared in a known data breach. Choose a different one.", "password"),

    PASSWORD_CONTAINS_PERSONAL_INFO("password_contains_personal_info",
            "The password must not contain your name or email address.", "password"),

    /**
     * Wrong, expired, already used, out of attempts, superseded or unknown:
     * all the same answer. Distinguishing them would let a caller tell a
     * real pending registration from the decoy ID handed out for an address
     * that is already registered.
     */
    INVALID_VERIFICATION_CODE("invalid_verification_code",
            "The verification code is invalid or has expired. Check the code or request a new one.", "code");

    private final String code;

    private final String detail;

    private final String field;

    IdentityProblem(String code, String detail, String field) {
        this.code = code;
        this.detail = detail;
        this.field = field;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
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
