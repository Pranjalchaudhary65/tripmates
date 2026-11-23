package com.tripmate.identity.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/**
 * Errors from the verification tiers, profile photo, vouching and the admin
 * review queue. None is a 401: the caller's session is fine.
 */
public enum VerificationProblem implements ProblemType {

    INVALID_PHONE_NUMBER(HttpStatus.BAD_REQUEST, "invalid_phone_number",
            "Enter a mobile number with its country code, such as +91 98765 43210.", "phoneNumber"),

    PHONE_ALREADY_VERIFIED(HttpStatus.CONFLICT, "phone_already_verified",
            "Your phone number is already verified.", null),

    /**
     * Only ever said to someone who has just entered the right code, so it
     * tells the holder of that phone what they already know.
     */
    PHONE_IN_USE(HttpStatus.CONFLICT, "phone_in_use",
            "This phone number is already verified on another TripMates account.", null),

    /** Wrong, expired, used, superseded, out of attempts, or not this account's: one answer. */
    INVALID_PHONE_CODE(HttpStatus.BAD_REQUEST, "invalid_phone_code",
            "The code is invalid or has expired. Check the code or request a new one.", "code"),

    PHONE_CODE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "phone_code_cooldown",
            "A code was sent moments ago. Wait for it, or request another after the wait in Retry-After.", null),

    PHONE_CODE_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "phone_code_daily_limit",
            "Too many codes were sent to this account today. Try again tomorrow.", null),

    PHONE_REQUIRED(HttpStatus.CONFLICT, "phone_verification_required",
            "Verify your phone number before the selfie check.", null),

    PROFILE_PHOTO_REQUIRED(HttpStatus.CONFLICT, "profile_photo_required",
            "Add a profile photo first. The selfie is compared against it.", null),

    SELFIE_ALREADY_VERIFIED(HttpStatus.CONFLICT, "selfie_already_verified",
            "Your selfie already matches your profile photo.", null),

    SELFIE_REVIEW_PENDING(HttpStatus.CONFLICT, "selfie_review_pending",
            "Your last selfie is waiting for a reviewer. You will see the result here.", null),

    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "invalid_image",
            "The image could not be used. Upload a JPEG or PNG photo, 64 to 4096 pixels on each side.", null),

    LIVENESS_CHALLENGE_INVALID(HttpStatus.BAD_REQUEST, "liveness_challenge_invalid",
            "This selfie check has expired or was already used. Start a new one.", null),

    VOUCH_REQUIRES_SELFIE_MATCH(HttpStatus.CONFLICT, "vouch_requires_selfie_match",
            "Complete the selfie check before vouching for someone.", null),

    VOUCH_LIMIT_REACHED(HttpStatus.CONFLICT, "vouch_limit_reached",
            "You are vouching for as many people as you can. Withdraw a vouch to vouch for someone new.", null),

    ALREADY_VOUCHING(HttpStatus.CONFLICT, "already_vouching",
            "You already vouch for this person.", null),

    CANNOT_VOUCH_FOR_SELF(HttpStatus.BAD_REQUEST, "cannot_vouch_for_self",
            "You cannot vouch for yourself.", "vouchedUserId"),

    VERIFICATION_ALREADY_DECIDED(HttpStatus.CONFLICT, "verification_already_decided",
            "This verification has already been decided.", null),

    /** An admin's own selfie goes to another admin, never to themselves. */
    CANNOT_REVIEW_OWN(HttpStatus.FORBIDDEN, "cannot_review_own_verification",
            "You cannot review your own verification.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    VerificationProblem(HttpStatus status, String code, String detail, String field) {
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
