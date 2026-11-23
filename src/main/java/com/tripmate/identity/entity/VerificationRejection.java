package com.tripmate.identity.entity;

/**
 * Why a verification was refused, from a fixed list (ck_idverif_rejection_reason)
 * rather than free text, so nothing a reviewer types can reach the user's
 * screen.
 */
public enum VerificationRejection {

    /** The two frames did not show a live person following the prompt. */
    LIVENESS_FAILED,

    /** The selfie did not match the profile photo closely enough to approve or to send for review. */
    LOW_SIMILARITY,

    /** A reviewer looked at a flagged selfie and refused it. */
    REVIEWER_REJECTED,

    /** The profile photo was replaced while the selfie waited for review, so there was nothing left to compare. */
    PROFILE_PHOTO_CHANGED,

    /** Nobody reviewed it before the selfie's retention window ended and the image was purged. */
    REVIEW_EXPIRED
}
