package com.tripmate.identity.service;

import com.tripmate.identity.entity.VerificationTier;

import java.util.UUID;

/**
 * @param deviceId the ID the browser's device cookie must hold from now on
 */
public record LoginResult(UUID userId, VerificationTier verificationTier, String deviceId) {

    @Override
    public String toString() {
        return "LoginResult[userId=" + userId + ", verificationTier=" + verificationTier + "]";
    }
}
