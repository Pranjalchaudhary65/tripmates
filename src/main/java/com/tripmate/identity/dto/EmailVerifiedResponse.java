package com.tripmate.identity.dto;

import com.tripmate.identity.entity.VerificationTier;

/**
 * Returned once the code is accepted. The tier lets the client move its
 * verification progress bar without a second call.
 */
public record EmailVerifiedResponse(boolean emailVerified, VerificationTier verificationTier) {

    public static EmailVerifiedResponse verified() {
        return new EmailVerifiedResponse(true, VerificationTier.EMAIL);
    }
}
