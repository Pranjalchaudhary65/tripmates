-- The trust score (DESIGN.md §8 and "Trust Score Engine spec"): reviews, the
-- input events the outbox relay hands the trust module, one snapshot per
-- change of score with its full breakdown, and the queue of users whose score
-- needs computing.

-- Double-blind reviews between people who travelled together. visible_at is
-- null until both people of the pair have reviewed each other, or the review
-- window has closed; every read for display or scoring filters on it.
CREATE TABLE reviews (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id       UUID NOT NULL REFERENCES trips (id),
    reviewer_id   UUID NOT NULL REFERENCES users (id),
    reviewee_id   UUID NOT NULL REFERENCES users (id),
    rating        SMALLINT NOT NULL,
    comment       VARCHAR(500),
    submitted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    visible_at    TIMESTAMPTZ,

    CONSTRAINT uq_reviews_pair UNIQUE (trip_id, reviewer_id, reviewee_id),
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_reviews_not_self CHECK (reviewer_id <> reviewee_id),
    CONSTRAINT ck_reviews_visible_after_submit CHECK (visible_at IS NULL OR visible_at >= submitted_at)
);

-- Scoring and the profile read only visible reviews of one person.
CREATE INDEX idx_reviews_reviewee_visible ON reviews (reviewee_id, visible_at) WHERE visible_at IS NOT NULL;
-- The window-close sweep looks for hidden reviews only.
CREATE INDEX idx_reviews_hidden ON reviews (trip_id) WHERE visible_at IS NULL;

-- A review's rating and words never change once submitted; only visible_at
-- moves, once, from null to a time. The comment may be erased (set to null)
-- when its writer's account is deleted, never rewritten.
CREATE FUNCTION guard_review_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'reviews cannot be removed: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.trip_id IS DISTINCT FROM OLD.trip_id
        OR NEW.reviewer_id IS DISTINCT FROM OLD.reviewer_id OR NEW.reviewee_id IS DISTINCT FROM OLD.reviewee_id
        OR NEW.rating IS DISTINCT FROM OLD.rating
        OR (NEW.comment IS DISTINCT FROM OLD.comment AND NEW.comment IS NOT NULL)
        OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
        OR (OLD.visible_at IS NOT NULL AND NEW.visible_at IS DISTINCT FROM OLD.visible_at) THEN
        RAISE EXCEPTION 'reviews cannot be edited once submitted'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_reviews_guard
    BEFORE UPDATE OR DELETE ON reviews
    FOR EACH ROW EXECUTE FUNCTION guard_review_change();
CREATE TRIGGER trg_reviews_no_truncate
    BEFORE TRUNCATE ON reviews
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

-- What the trust module was told, one row per outbox event it accepted. The
-- event types are the whole list of things that may ever move a score from
-- outside the trust module; a comfort rating, a safety cancellation, an SOS
-- or any other safety action has no type here, so the database itself
-- refuses to record one.
CREATE TABLE trust_score_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users (id),
    event_type       VARCHAR(30) NOT NULL,
    detail           VARCHAR(30),
    related_id       UUID,
    source_event_id  UUID NOT NULL REFERENCES outbox_events (id),
    occurred_at      TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A redelivered outbox event is recorded once.
    CONSTRAINT uq_trust_events_source UNIQUE (source_event_id),
    CONSTRAINT ck_trust_events_type CHECK (event_type IN (
        'VERIFICATION_TIER_CHANGE', 'VOUCH_GIVEN', 'VOUCH_REVOKED', 'VOUCH_PENALIZED',
        'MEETUP_NO_SHOW', 'MEETUP_LATE_CANCEL', 'SETTLEMENT_OVERDUE', 'CHAT_MESSAGE_FLAGGED')),
    CONSTRAINT ck_trust_events_detail CHECK (detail IS NULL OR detail ~ '^[A-Z_]{1,30}$')
);

CREATE INDEX idx_trust_events_user ON trust_score_events (user_id, occurred_at);

-- Every score a user has had, with the breakdown that produced it, so "why is
-- my score X" and any dispute can be answered for any point in time.
CREATE TABLE trust_score_snapshots (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users (id),
    score            NUMERIC(5, 2) NOT NULL,
    components       JSONB NOT NULL,
    formula_version  VARCHAR(10) NOT NULL,
    computed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_trust_snapshots_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_trust_snapshots_components CHECK (jsonb_typeof(components) = 'object')
);

CREATE INDEX idx_trust_snapshots_user_time ON trust_score_snapshots (user_id, computed_at DESC);

CREATE TRIGGER trg_trust_events_append_only
    BEFORE UPDATE OR DELETE ON trust_score_events
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_trust_events_no_truncate
    BEFORE TRUNCATE ON trust_score_events
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_trust_snapshots_append_only
    BEFORE UPDATE OR DELETE ON trust_score_snapshots
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_trust_snapshots_no_truncate
    BEFORE TRUNCATE ON trust_score_snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

REVOKE UPDATE, DELETE, TRUNCATE ON trust_score_events, trust_score_snapshots FROM PUBLIC;
REVOKE DELETE, TRUNCATE ON reviews FROM PUBLIC;

-- Whose score needs computing. dirty_since is set by anything that changes an
-- input and cleared by the job that recomputes; computed_at drives the daily
-- refresh, since ratings and penalties fade with time even when nothing new
-- happens.
CREATE TABLE trust_score_state (
    user_id      UUID PRIMARY KEY REFERENCES users (id),
    dirty_since  TIMESTAMPTZ,
    computed_at  TIMESTAMPTZ
);

CREATE INDEX idx_trust_state_dirty ON trust_score_state (dirty_since) WHERE dirty_since IS NOT NULL;
CREATE INDEX idx_trust_state_computed ON trust_score_state (computed_at);

-- Everyone who can be seen by anyone gets a score on the first run, instead
-- of keeping the 0 default that a min_trust_score filter would hide.
INSERT INTO trust_score_state (user_id, dirty_since)
SELECT id, now() FROM users WHERE deleted_at IS NULL AND id_verification_tier <> 'NONE';
