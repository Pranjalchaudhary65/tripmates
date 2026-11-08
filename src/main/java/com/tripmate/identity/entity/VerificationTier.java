package com.tripmate.identity.entity;

/** Ordered from weakest to strongest; values match ck_users_id_verification_tier. */
public enum VerificationTier {
    NONE, EMAIL, PHONE, SELFIE_MATCH
}
