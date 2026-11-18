package com.tripmate.identity.entity;

/** Why a refresh token family was ended. Values match ck_rtf_revoked_reason. */
public enum SessionRevocationReason {

    /** The user signed out of this session. */
    LOGOUT,

    /** A refresh token of this family was presented after it had been rotated out. */
    REUSE_DETECTED,

    /** The user signed out of every session at once. */
    REVOKE_ALL_DEVICES,

    /** Ended by moderation. */
    ADMIN
}
