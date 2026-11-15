package com.tripmate.identity.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

public enum LoginProblem implements ProblemType {

    /**
     * Unknown email, wrong password, unverified email, disabled or locked
     * account: one answer for all of them. The wording covers the unverified
     * case so a real user who has not verified yet knows where to look,
     * without the response saying which case applied.
     */
    INVALID_CREDENTIALS("invalid_credentials",
            "The email or password is incorrect, or the email address has not been verified yet. "
                    + "If you just signed up, use the code we emailed you first."),

    /**
     * Asked for after repeated failures under the typed email. The count is
     * kept for every typed email, registered or not, so being asked reveals
     * nothing about whether an account exists.
     */
    CAPTCHA_REQUIRED("captcha_required",
            "Complete the CAPTCHA and try again.");

    private final String code;

    private final String detail;

    LoginProblem(String code, String detail) {
        this.code = code;
        this.detail = detail;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNAUTHORIZED;
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
