package com.tripmate.identity.entity;

/**
 * Why a login attempt was refused. Recorded in login_history for the account
 * owner and for abuse review only - the client gets one identical answer for
 * every reason except {@link #CAPTCHA_FAILED}, which is decided by the failure
 * count under the typed email, not by whether the account exists.
 */
public enum LoginFailureReason {
    UNKNOWN_EMAIL,
    BAD_PASSWORD,
    EMAIL_NOT_VERIFIED,
    ACCOUNT_DISABLED,
    LOCKED,
    CAPTCHA_FAILED
}
