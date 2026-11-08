-- Identity & Auth tables from DESIGN.md §1: users, user_encryption_keys,
-- otp_codes. The remaining identity tables arrive with the features that
-- use them.

CREATE TABLE users (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                   CITEXT NOT NULL,
    password_hash           TEXT NOT NULL,
    full_name               TEXT NOT NULL,
    date_of_birth           DATE NOT NULL,
    gender                  VARCHAR(20) NOT NULL,
    phone_ciphertext        BYTEA,
    phone_blind_index       VARCHAR(64),
    upi_vpa                 VARCHAR(100),
    profile_photo_key       TEXT,
    email_verified_at       TIMESTAMPTZ,
    phone_verified_at       TIMESTAMPTZ,
    id_verification_tier    VARCHAR(20) NOT NULL DEFAULT 'NONE',
    totp_secret_ciphertext  BYTEA,
    totp_enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    trust_score             NUMERIC(5,2) NOT NULL DEFAULT 0,
    trust_score_updated_at  TIMESTAMPTZ,
    account_status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    role                    VARCHAR(20) NOT NULL DEFAULT 'USER',
    -- Nullable on purpose: a user row exists before its first data key, and
    -- the foreign key is added below, once user_encryption_keys exists.
    encryption_key_id       UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ,

    -- Backstop for the service-layer age check. "Today" is pinned to IST,
    -- the same zone AgePolicy uses, so the two can never disagree on a
    -- birthday because of the session's TimeZone setting. now() is not
    -- immutable, but PostgreSQL treats CHECKs as if they were and evaluates
    -- them only on INSERT/UPDATE of the row. That is safe here because the
    -- condition only gets easier to satisfy as time passes: a row that
    -- passed once passes on every later update and on restore.
    CONSTRAINT ck_users_adult
        CHECK (date_of_birth <= (now() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '18 years'),
    CONSTRAINT ck_users_gender
        CHECK (gender IN ('MALE', 'FEMALE', 'NON_BINARY', 'PREFER_NOT_TO_SAY')),
    CONSTRAINT ck_users_id_verification_tier
        CHECK (id_verification_tier IN ('NONE', 'EMAIL', 'PHONE', 'SELFIE_MATCH')),
    CONSTRAINT ck_users_account_status
        CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'BANNED', 'DELETED')),
    CONSTRAINT ck_users_role
        CHECK (role IN ('USER', 'ADMIN'))
);

-- Unique among live accounts only, so a soft-deleted user's email can be
-- registered again.
CREATE UNIQUE INDEX idx_users_email_active ON users (email) WHERE deleted_at IS NULL;

-- One-account-per-phone checks and phone lookups match on the HMAC, never
-- by decrypting phone_ciphertext.
CREATE INDEX idx_users_phone_blind_index ON users (phone_blind_index);

-- Moderation queries filter on non-ACTIVE accounts, a tiny fraction of rows.
CREATE INDEX idx_users_account_status ON users (account_status) WHERE account_status <> 'ACTIVE';

CREATE TABLE user_encryption_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users (id),
    wrapped_dek  BYTEA NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ,

    -- Target of the composite foreign key from users below.
    CONSTRAINT uq_uek_id_user UNIQUE (id, user_id)
);

-- At most one active data key per user: decrypting any PII field fetches
-- "the" active key, which must not be ambiguous.
CREATE UNIQUE INDEX idx_uek_user_active ON user_encryption_keys (user_id) WHERE active;

-- Closes the circular reference. Composite rather than encryption_key_id
-- alone, so a user row can only point at a key whose user_id is that same
-- user, never at another user's key. MATCH SIMPLE (the default) skips the
-- check while encryption_key_id is NULL.
ALTER TABLE users
    ADD CONSTRAINT fk_users_encryption_key
        FOREIGN KEY (encryption_key_id, id) REFERENCES user_encryption_keys (id, user_id);

CREATE TABLE otp_codes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users (id),
    channel        VARCHAR(10) NOT NULL,
    purpose        VARCHAR(20) NOT NULL,
    -- Keyed HMAC of the code, never the code itself.
    code_hash      TEXT NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    attempt_count  SMALLINT NOT NULL DEFAULT 0,
    max_attempts   SMALLINT NOT NULL DEFAULT 5,
    consumed_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_otp_codes_channel CHECK (channel IN ('EMAIL', 'PHONE')),
    CONSTRAINT ck_otp_codes_purpose CHECK (purpose IN ('VERIFY', 'LOGIN_2FA', 'PASSWORD_RESET')),
    CONSTRAINT ck_otp_codes_attempts CHECK (max_attempts > 0 AND attempt_count BETWEEN 0 AND max_attempts)
);

-- Resend and re-registration look up the live codes for a user and purpose.
CREATE INDEX idx_otp_user_purpose_active ON otp_codes (user_id, purpose) WHERE consumed_at IS NULL;
