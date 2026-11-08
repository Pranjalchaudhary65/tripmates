package com.tripmate.identity.entity;

/** Values match the ck_otp_codes_purpose CHECK constraint. */
public enum OtpPurpose {
    VERIFY, LOGIN_2FA, PASSWORD_RESET
}
