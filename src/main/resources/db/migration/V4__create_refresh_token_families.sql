-- refresh_token_families from DESIGN.md §1: one row per signed-in session.
-- The rotating token hash lives in Redis (refresh:{id}); this table is the
-- durable record of whether the session is alive, and what the sessions
-- list shows.

CREATE TABLE refresh_token_families (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users (id),
    -- Derived by the server from a fixed vocabulary, never client text.
    device_name      VARCHAR(64) NOT NULL,
    ip_address       INET NOT NULL,
    -- Client-controlled; kept for audit only and never returned by the API.
    user_agent       TEXT,
    last_ip_address  INET NOT NULL,
    last_used_at     TIMESTAMPTZ NOT NULL,
    -- Absolute: rotating the token never moves it.
    expires_at       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at       TIMESTAMPTZ,
    revoked_reason   VARCHAR(30),

    CONSTRAINT ck_rtf_revoked_reason
        CHECK (revoked_reason IN ('LOGOUT', 'REUSE_DETECTED', 'REVOKE_ALL_DEVICES', 'ADMIN')),
    -- A family is either live with no reason, or revoked with one.
    CONSTRAINT ck_rtf_revocation CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT ck_rtf_lifetime CHECK (expires_at > created_at),
    CONSTRAINT ck_rtf_user_agent_length CHECK (char_length(user_agent) <= 512)
);

-- "Revoke all devices" and the sessions list both read every live family
-- of one user; revoked families, the bulk of the table over time, are
-- left out of the index.
CREATE INDEX idx_rtf_user_active ON refresh_token_families (user_id) WHERE revoked_at IS NULL;
