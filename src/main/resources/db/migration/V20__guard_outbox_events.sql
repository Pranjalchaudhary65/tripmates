-- The outbox relay's bookkeeping, and the guard that keeps every event as it
-- was written (DESIGN.md §9 and "Append-only enforcement").
--
-- An event's facts (what happened, to what, when, with which payload) never
-- change and the row is never deleted. The relay records only that it has
-- dealt with an event: processed_at once delivered to every handler, or
-- dead_at once it has failed max-attempts times, each set once and never
-- moved or cleared, and attempts, which only grows. A trigger holds this for
-- every role, the table owner included, for the same reason as the ledger
-- triggers in V18: the application connects as the owner, and a REVOKE cannot
-- restrict an owner.

ALTER TABLE outbox_events
    ADD COLUMN dead_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_outbox_settled_once CHECK (processed_at IS NULL OR dead_at IS NULL);

-- The relay's only query: undelivered, live events in FIFO order.
DROP INDEX idx_outbox_unprocessed;
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE processed_at IS NULL AND dead_at IS NULL;

CREATE FUNCTION guard_outbox_event_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'outbox_events is append-only: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'outbox_events is append-only: an event''s content cannot change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (OLD.processed_at IS NOT NULL AND NEW.processed_at IS DISTINCT FROM OLD.processed_at)
        OR (OLD.dead_at IS NOT NULL AND NEW.dead_at IS DISTINCT FROM OLD.dead_at)
        OR NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'outbox_events is append-only: delivery is recorded once and never undone'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_outbox_events_guard
    BEFORE UPDATE OR DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION guard_outbox_event_change();
CREATE TRIGGER trg_outbox_events_no_truncate
    BEFORE TRUNCATE ON outbox_events
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

REVOKE DELETE, TRUNCATE ON outbox_events FROM PUBLIC;
