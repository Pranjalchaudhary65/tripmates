-- login_history from DESIGN.md §1: one row per login attempt that reached
-- the credential check, successful or not.

CREATE TABLE login_history (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Null when the email matched no account; the attempt is still recorded.
    user_id          UUID REFERENCES users (id),
    email_attempted  CITEXT NOT NULL,
    ip_address       INET NOT NULL,
    user_agent       TEXT,
    -- SHA-256 of the browser's device cookie, never the cookie itself.
    device_id_hash   VARCHAR(64),
    success          BOOLEAN NOT NULL,
    failure_reason   VARCHAR(30),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_login_history_failure_reason
        CHECK (failure_reason IN ('UNKNOWN_EMAIL', 'BAD_PASSWORD', 'EMAIL_NOT_VERIFIED',
                                  'ACCOUNT_DISABLED', 'LOCKED', 'CAPTCHA_FAILED')),
    -- A row is either a success with no reason or a failure with one.
    CONSTRAINT ck_login_history_outcome CHECK (success = (failure_reason IS NULL)),
    CONSTRAINT ck_login_history_success_has_user CHECK (NOT success OR user_id IS NOT NULL),
    CONSTRAINT ck_login_history_device_id_hash CHECK (device_id_hash ~ '^[0-9a-f]{64}$')
);

-- A user's own login history, newest first.
CREATE INDEX idx_login_history_user_time ON login_history (user_id, created_at DESC);

-- Recent failures by IP across all accounts (credential-stuffing review).
CREATE INDEX idx_login_history_ip_time ON login_history (ip_address, created_at);

-- "Has this browser signed in to this account before": decides the
-- new-device alert and whether the browser gets its own lockout counter.
CREATE INDEX idx_login_history_user_device ON login_history (user_id, device_id_hash) WHERE success;
