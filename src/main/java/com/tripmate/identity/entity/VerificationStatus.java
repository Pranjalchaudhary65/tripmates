package com.tripmate.identity.entity;

/** Values match ck_idverif_status. PENDING is a selfie waiting for a reviewer; everything else is decided. */
public enum VerificationStatus {
    PENDING, APPROVED, REJECTED
}
