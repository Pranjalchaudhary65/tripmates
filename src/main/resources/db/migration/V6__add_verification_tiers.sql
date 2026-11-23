-- Verification tiers from DESIGN.md §1: the phone and selfie tiers on users,
-- identity_verifications, vouches, and the outbox the trust score reads its
-- input events from (§9).

-- One live account per verified phone number. An unconfirmed number may sit
-- on several accounts at once: nobody has proven they hold it yet.
CREATE UNIQUE INDEX idx_users_phone_verified_unique ON users (phone_blind_index)
    WHERE phone_verified_at IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE users
    -- The ciphertext and its blind index are written and cleared together.
    ADD CONSTRAINT ck_users_phone_pair
        CHECK ((phone_ciphertext IS NULL) = (phone_blind_index IS NULL)),
    ADD CONSTRAINT ck_users_phone_secret_has_key
        CHECK (phone_ciphertext IS NULL OR encryption_key_id IS NOT NULL),
    ADD CONSTRAINT ck_users_phone_verified_has_number
        CHECK (phone_verified_at IS NULL OR phone_ciphertext IS NOT NULL),
    ADD CONSTRAINT ck_users_blind_index_format
        CHECK (phone_blind_index IS NULL OR phone_blind_index ~ '^[0-9a-f]{64}$'),
    -- A tier is only ever as high as the evidence on the row: no code path,
    -- and no hand-run UPDATE, can claim a tier the account has not reached.
    ADD CONSTRAINT ck_users_tier_evidence
        CHECK (id_verification_tier = 'NONE'
               OR (email_verified_at IS NOT NULL
                   AND (id_verification_tier NOT IN ('PHONE', 'SELFIE_MATCH') OR phone_verified_at IS NOT NULL)
                   AND (id_verification_tier <> 'SELFIE_MATCH' OR profile_photo_key IS NOT NULL)));

CREATE TABLE identity_verifications (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users (id),
    tier              VARCHAR(20) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    similarity_score  NUMERIC(4, 3),
    selfie_image_key  TEXT,
    purge_due_at      TIMESTAMPTZ,
    purged_at         TIMESTAMPTZ,
    reviewer_id       UUID REFERENCES users (id),
    rejection_reason  VARCHAR(30),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at       TIMESTAMPTZ,

    CONSTRAINT ck_idverif_tier CHECK (tier IN ('PHONE', 'SELFIE_MATCH')),
    CONSTRAINT ck_idverif_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_idverif_rejection_reason
        CHECK (rejection_reason IN ('LIVENESS_FAILED', 'LOW_SIMILARITY', 'REVIEWER_REJECTED',
                                    'PROFILE_PHOTO_CHANGED', 'REVIEW_EXPIRED')),
    CONSTRAINT ck_idverif_similarity CHECK (similarity_score BETWEEN 0 AND 1),
    -- A phone verification never holds an image or a score.
    CONSTRAINT ck_idverif_phone_has_no_image
        CHECK (tier <> 'PHONE' OR (similarity_score IS NULL AND selfie_image_key IS NULL
                                   AND purge_due_at IS NULL AND purged_at IS NULL)),
    -- Every selfie has a purge deadline from the moment it is stored.
    CONSTRAINT ck_idverif_selfie_has_deadline CHECK (tier <> 'SELFIE_MATCH' OR purge_due_at IS NOT NULL),
    CONSTRAINT ck_idverif_purged_has_no_image CHECK (purged_at IS NULL OR selfie_image_key IS NULL),
    CONSTRAINT ck_idverif_decided CHECK ((status = 'PENDING') = (reviewed_at IS NULL)),
    CONSTRAINT ck_idverif_rejected_has_reason CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CONSTRAINT ck_idverif_reviewer_not_self CHECK (reviewer_id IS NULL OR reviewer_id <> user_id)
);

-- "Does this user have a pending selfie review" and the admin queue.
CREATE INDEX idx_idverif_user_tier_status ON identity_verifications (user_id, tier, status);

-- The purge job's only query: rows still holding an image, by deadline.
CREATE INDEX idx_idverif_purge_due ON identity_verifications (purge_due_at) WHERE selfie_image_key IS NOT NULL;

-- At most one selfie waiting for a reviewer per user, so two submissions
-- racing each other cannot both land in the queue.
CREATE UNIQUE INDEX idx_idverif_one_pending_selfie ON identity_verifications (user_id)
    WHERE tier = 'SELFIE_MATCH' AND status = 'PENDING';

-- The admin queue, oldest first.
CREATE INDEX idx_idverif_pending_queue ON identity_verifications (created_at)
    WHERE tier = 'SELFIE_MATCH' AND status = 'PENDING';

CREATE TABLE vouches (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    voucher_user_id  UUID NOT NULL REFERENCES users (id),
    vouched_user_id  UUID NOT NULL REFERENCES users (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at       TIMESTAMPTZ,

    CONSTRAINT ck_vouches_not_self CHECK (vouched_user_id <> voucher_user_id),
    CONSTRAINT ck_vouches_revoked_after_created CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT uq_vouches_pair UNIQUE (voucher_user_id, vouched_user_id)
);

-- Who actively vouches for a user: the vouch bonus, and the vouchers to
-- penalise when that user is suspended.
CREATE INDEX idx_vouches_vouched_active ON vouches (vouched_user_id) WHERE revoked_at IS NULL;

-- How many people a voucher actively vouches for: the cap.
CREATE INDEX idx_vouches_voucher_active ON vouches (voucher_user_id) WHERE revoked_at IS NULL;

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(30) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    attempts        SMALLINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_outbox_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

-- The relay's only query, in FIFO order.
CREATE INDEX idx_outbox_unprocessed ON outbox_events (created_at) WHERE processed_at IS NULL;
