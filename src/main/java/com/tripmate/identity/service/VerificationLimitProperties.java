package com.tripmate.identity.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Per-account limits on the verification routes, on top of the per-code
 * attempt limits and daily code cap.
 *
 * @param phoneCode        sending a phone code: each one is an SMS someone pays for
 * @param phoneConfirm     entering a phone code
 * @param selfieChallenge  starting a selfie check
 * @param selfieSubmission submitting one: a real liveness provider bills per
 *                         check, and an unlimited matcher is an oracle for
 *                         finding a photo that passes
 * @param profilePhoto     replacing the profile photo
 * @param vouch            vouching for someone
 * @param emergencyContact setting or removing the emergency contact
 * @param upiId            setting or removing the UPI ID others pay the user at
 * @param maxActiveVouches how many people one account can vouch for at once
 */
@ConfigurationProperties("tripmate.identity.verification-limits")
public record VerificationLimitProperties(RateLimit phoneCode, RateLimit phoneConfirm, RateLimit selfieChallenge,
                                          RateLimit selfieSubmission, RateLimit profilePhoto, RateLimit vouch,
                                          RateLimit emergencyContact, RateLimit upiId, int maxActiveVouches) {

    public VerificationLimitProperties {
        String prefix = "tripmate.identity.verification-limits.";
        Objects.requireNonNull(phoneCode, prefix + "phone-code is required");
        Objects.requireNonNull(phoneConfirm, prefix + "phone-confirm is required");
        Objects.requireNonNull(selfieChallenge, prefix + "selfie-challenge is required");
        Objects.requireNonNull(selfieSubmission, prefix + "selfie-submission is required");
        Objects.requireNonNull(profilePhoto, prefix + "profile-photo is required");
        Objects.requireNonNull(vouch, prefix + "vouch is required");
        Objects.requireNonNull(emergencyContact, prefix + "emergency-contact is required");
        Objects.requireNonNull(upiId, prefix + "upi-id is required");
        if (maxActiveVouches < 1) {
            throw new IllegalArgumentException(prefix + "max-active-vouches must be positive");
        }
    }
}
