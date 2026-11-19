package com.tripmate.identity.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/**
 * Errors from managing the caller's own second factor. None is a 401: the
 * caller's session is fine, and a 401 would send the client off to refresh
 * its token and retry.
 */
public enum TwoFactorProblem implements ProblemType {

    /**
     * Wrong password, or wrong, replayed or used code, on a change that needs
     * both. One answer, so a stolen session cannot tell which half it got wrong.
     */
    REAUTHENTICATION_FAILED(HttpStatus.BAD_REQUEST, "reauthentication_failed",
            "The password or code is incorrect.", null),

    /** The first code from a secret being set up did not match it. */
    INVALID_TOTP_CODE(HttpStatus.BAD_REQUEST, "invalid_totp_code",
            "That code does not match. Check the time on your phone and enter the current code.", "code"),

    ALREADY_ENABLED(HttpStatus.CONFLICT, "two_factor_already_enabled",
            "Two-factor authentication is already on. Turn it off first to move it to a new device.", null),

    NOT_ENABLED(HttpStatus.CONFLICT, "two_factor_not_enabled",
            "Two-factor authentication is not on for this account.", null),

    NO_PENDING_SETUP(HttpStatus.CONFLICT, "two_factor_setup_not_started",
            "Start two-factor setup first, then enter the code it shows.", null),

    /** An admin account must keep its second factor; the database refuses it too. */
    REQUIRED_FOR_ADMIN(HttpStatus.FORBIDDEN, "two_factor_required_for_admin",
            "Admin accounts cannot turn off two-factor authentication.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    TwoFactorProblem(HttpStatus status, String code, String detail, String field) {
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
