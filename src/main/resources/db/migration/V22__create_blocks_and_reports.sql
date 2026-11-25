-- Blocking and reporting (DESIGN.md §9 blocks_reports, as built: two tables).
--
-- A block is a relationship between two accounts, so identity owns it, next
-- to vouches; it can be lifted, so rows are deleted on unblock. Other modules
-- read it only through user_block_facts, which lists each block from both
-- sides: a block hides two people from each other whichever of them made it.
--
-- A report is a case for a moderator, owned by the moderation module. Its
-- details are sealed under the reporter's data key. Reports are never
-- deleted; a moderator closes one as ACTIONED or DISMISSED.

CREATE TABLE user_blocks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    blocker_id  UUID NOT NULL REFERENCES users (id),
    blocked_id  UUID NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_user_blocks_pair UNIQUE (blocker_id, blocked_id),
    CONSTRAINT ck_user_blocks_not_self CHECK (blocker_id <> blocked_id)
);

-- The reverse direction of the unique index, for "has anyone blocked me".
CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_id, blocker_id);

CREATE VIEW user_block_facts AS
SELECT blocker_id AS user_id, blocked_id AS other_user_id FROM user_blocks
UNION ALL
SELECT blocked_id AS user_id, blocker_id AS other_user_id FROM user_blocks;

CREATE TABLE reports (
    id                  UUID PRIMARY KEY,
    reporter_id         UUID NOT NULL REFERENCES users (id),
    reported_id         UUID NOT NULL REFERENCES users (id),
    trip_id             UUID REFERENCES trips (id),
    reason              VARCHAR(30) NOT NULL,
    details_ciphertext  BYTEA,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by         UUID REFERENCES users (id),
    reviewed_at         TIMESTAMPTZ,

    CONSTRAINT ck_reports_not_self CHECK (reporter_id <> reported_id),
    CONSTRAINT ck_reports_reason CHECK (reason IN
        ('HARASSMENT', 'SCAM_OR_MONEY_REQUEST', 'FAKE_PROFILE', 'UNSAFE_BEHAVIOUR', 'OTHER')),
    CONSTRAINT ck_reports_status CHECK (status IN ('OPEN', 'ACTIONED', 'DISMISSED')),
    CONSTRAINT ck_reports_reviewed CHECK ((status = 'OPEN') = (reviewed_at IS NULL)
        AND (reviewed_at IS NULL) = (reviewed_by IS NULL)),
    CONSTRAINT ck_reports_reviewer_not_party CHECK (reviewed_by IS NULL
        OR (reviewed_by <> reporter_id AND reviewed_by <> reported_id))
);

-- One open report per reporter about one person: reporting again adds nothing
-- to the auto-suspend count.
CREATE UNIQUE INDEX uq_reports_open_pair ON reports (reporter_id, reported_id) WHERE status = 'OPEN';
-- The auto-suspend count: reports about one person in a rolling window.
CREATE INDEX idx_reports_reported ON reports (reported_id, created_at);
-- The moderation queue, oldest first.
CREATE INDEX idx_reports_open ON reports (created_at) WHERE status = 'OPEN';

CREATE FUNCTION guard_report_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'reports cannot be removed: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.reporter_id IS DISTINCT FROM OLD.reporter_id
        OR NEW.reported_id IS DISTINCT FROM OLD.reported_id OR NEW.trip_id IS DISTINCT FROM OLD.trip_id
        OR NEW.reason IS DISTINCT FROM OLD.reason OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (NEW.details_ciphertext IS DISTINCT FROM OLD.details_ciphertext AND NEW.details_ciphertext IS NOT NULL)
        OR OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'reports cannot be edited; only an open report can be closed once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_reports_guard
    BEFORE UPDATE OR DELETE ON reports
    FOR EACH ROW EXECUTE FUNCTION guard_report_change();
CREATE TRIGGER trg_reports_no_truncate
    BEFORE TRUNCATE ON reports
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

REVOKE DELETE, TRUNCATE ON reports FROM PUBLIC;
