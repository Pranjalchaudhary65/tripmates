-- Compliance and audit (DESIGN.md §9): the hash-chained audit log and its
-- integrity checks, consent records and data requests.

-- Each row's hash covers the previous row's hash and its own fields, so an
-- edit or a removed row anywhere breaks every later hash. The trigger below
-- fills sequence_no, prev_hash and row_hash under one transaction-scoped
-- advisory lock, so two writers can never chain onto the same row and the
-- sequence is gapless. The hashed text is audit_row_text(), which the
-- integrity check reads back to recompute every hash on its own.
CREATE TABLE audit_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no    BIGINT NOT NULL,
    prev_hash      VARCHAR(64) NOT NULL,
    row_hash       VARCHAR(64) NOT NULL,
    actor_user_id  UUID REFERENCES users (id),
    event_type     VARCHAR(50) NOT NULL,
    entity_type    VARCHAR(30),
    entity_id      UUID,
    metadata       JSONB NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_audit_sequence UNIQUE (sequence_no),
    CONSTRAINT ck_audit_sequence_positive CHECK (sequence_no >= 1),
    CONSTRAINT ck_audit_hashes CHECK (prev_hash ~ '^[0-9a-f]{64}$' AND row_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_event_type CHECK (event_type ~ '^[A-Z_]{1,50}$'),
    CONSTRAINT ck_audit_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_actor ON audit_log (actor_user_id, sequence_no);

-- The exact text a row's hash covers: fixed field order, '|' between them,
-- the timestamp in UTC to the microsecond, metadata as jsonb prints it.
CREATE FUNCTION audit_row_text(seq BIGINT, prev VARCHAR, actor UUID, event VARCHAR, entity_type VARCHAR,
                               entity_id UUID, metadata JSONB, created TIMESTAMPTZ) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $$
SELECT seq::text || '|' || prev || '|' || coalesce(actor::text, '') || '|' || event || '|'
       || coalesce(entity_type, '') || '|' || coalesce(entity_id::text, '') || '|' || metadata::text || '|'
       || to_char(created AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
$$;

CREATE FUNCTION chain_audit_row() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    last_seq  BIGINT;
    last_hash VARCHAR(64);
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('audit_log_chain', 0));
    SELECT sequence_no, row_hash INTO last_seq, last_hash FROM audit_log ORDER BY sequence_no DESC LIMIT 1;
    NEW.sequence_no := coalesce(last_seq, 0) + 1;
    NEW.prev_hash := coalesce(last_hash, repeat('0', 64));
    NEW.row_hash := encode(digest(audit_row_text(NEW.sequence_no, NEW.prev_hash, NEW.actor_user_id, NEW.event_type,
                                                 NEW.entity_type, NEW.entity_id, NEW.metadata, NEW.created_at),
                                  'sha256'), 'hex');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_log_chain
    BEFORE INSERT ON audit_log
    FOR EACH ROW EXECUTE FUNCTION chain_audit_row();
CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM PUBLIC;

-- What each integrity sweep found.
CREATE TABLE audit_integrity_checks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    checked_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    rows_checked      BIGINT NOT NULL,
    intact            BOOLEAN NOT NULL,
    first_broken_seq  BIGINT,

    CONSTRAINT ck_audit_check_result CHECK (intact = (first_broken_seq IS NULL))
);

CREATE INDEX idx_audit_checks_time ON audit_integrity_checks (checked_at DESC);

CREATE TRIGGER trg_audit_checks_append_only
    BEFORE UPDATE OR DELETE ON audit_integrity_checks
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();

-- One row per grant or withdrawal; a consent's current state is its latest
-- row. Never updated, so what someone agreed to, and when, is kept.
CREATE TABLE consent_records (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users (id),
    consent_type    VARCHAR(30) NOT NULL,
    policy_version  VARCHAR(20) NOT NULL,
    granted         BOOLEAN NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_consent_type CHECK (consent_type IN ('TOS', 'PRIVACY_POLICY', 'LOCATION_SHARING', 'MARKETING'))
);

CREATE INDEX idx_consent_user_type ON consent_records (user_id, consent_type, created_at DESC);

CREATE TRIGGER trg_consent_records_append_only
    BEFORE UPDATE OR DELETE ON consent_records
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();

-- Everyone who signed up before consents were recorded accepted the terms
-- shown at sign-up; recorded as the first published version.
INSERT INTO consent_records (user_id, consent_type, policy_version, granted, created_at)
SELECT u.id, c.type, '2026-09', TRUE, u.created_at
FROM users u CROSS JOIN (VALUES ('TOS'), ('PRIVACY_POLICY')) AS c (type)
WHERE u.deleted_at IS NULL;

-- A user's request for a copy of their data, or to delete their account.
-- The export is sealed under the user's own data key, so deleting the
-- account destroys any export still waiting, and it is cleared when it
-- expires.
CREATE TABLE data_requests (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES users (id),
    type               VARCHAR(20) NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    export_ciphertext  BYTEA,
    requested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ,
    attempts           SMALLINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_data_requests_type CHECK (type IN ('EXPORT', 'DELETION')),
    CONSTRAINT ck_data_requests_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_data_requests_done CHECK ((status = 'PENDING') = (completed_at IS NULL)),
    CONSTRAINT ck_data_requests_export CHECK (export_ciphertext IS NULL OR (type = 'EXPORT' AND status = 'COMPLETED'))
);

-- One request of each type in progress per user.
CREATE UNIQUE INDEX uq_data_requests_pending ON data_requests (user_id, type) WHERE status = 'PENDING';
CREATE INDEX idx_data_requests_pending ON data_requests (requested_at) WHERE status = 'PENDING';
CREATE INDEX idx_data_requests_user ON data_requests (user_id, requested_at DESC);
