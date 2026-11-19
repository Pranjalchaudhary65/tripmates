-- Two-factor authentication from DESIGN.md §1: TOTP replay tracking on
-- users, totp_backup_codes, the per-session second-factor flag, and the
-- constraints that make an encrypted field and an admin account safe by
-- construction.

-- Time step of the newest TOTP code accepted for this account. A code is
-- only accepted for a later step, so a code cannot be used twice.
ALTER TABLE users ADD COLUMN totp_last_used_step BIGINT;

ALTER TABLE users
    ADD CONSTRAINT ck_users_totp_enabled_has_secret
        CHECK (NOT totp_enabled OR totp_secret_ciphertext IS NOT NULL),
    -- Ciphertext is meaningless without the data key that encrypted it.
    ADD CONSTRAINT ck_users_totp_secret_has_key
        CHECK (totp_secret_ciphertext IS NULL OR encryption_key_id IS NOT NULL),
    -- An admin account without a second factor cannot exist, however the
    -- row is written. Promote an account only after it has turned 2FA on.
    ADD CONSTRAINT ck_users_admin_has_two_factor
        CHECK (role <> 'ADMIN' OR totp_enabled);

-- A revoked data key is a shredded one: clearing the flag while keeping the
-- wrapped key would leave it recoverable by anyone holding the KEK.
ALTER TABLE user_encryption_keys
    ADD CONSTRAINT ck_uek_revocation CHECK (active = (revoked_at IS NULL)),
    ADD CONSTRAINT ck_uek_shredded CHECK (active = (octet_length(wrapped_dek) > 0));

CREATE TABLE totp_backup_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id),
    -- Keyed HMAC of the code, never the code itself.
    code_hash   TEXT NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_backup_codes_hash CHECK (code_hash ~ '^[0-9a-f]{64}$')
);

-- A sign-in with a backup code compares against the user's unused codes only.
CREATE INDEX idx_backup_codes_user_unused ON totp_backup_codes (user_id) WHERE used_at IS NULL;

-- How the session was signed in, so the amr claim survives every refresh.
ALTER TABLE refresh_token_families
    ADD COLUMN second_factor_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Turning 2FA on or off ends the account's other sessions under its own reason.
ALTER TABLE refresh_token_families
    DROP CONSTRAINT ck_rtf_revoked_reason,
    ADD CONSTRAINT ck_rtf_revoked_reason
        CHECK (revoked_reason IN ('LOGOUT', 'REUSE_DETECTED', 'REVOKE_ALL_DEVICES', 'ADMIN', 'SECURITY_CHANGE'));

ALTER TABLE login_history
    DROP CONSTRAINT ck_login_history_failure_reason,
    ADD CONSTRAINT ck_login_history_failure_reason
        CHECK (failure_reason IN ('UNKNOWN_EMAIL', 'BAD_PASSWORD', 'EMAIL_NOT_VERIFIED',
                                  'ACCOUNT_DISABLED', 'LOCKED', 'CAPTCHA_FAILED', 'BAD_SECOND_FACTOR'));
