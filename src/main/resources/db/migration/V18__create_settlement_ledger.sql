-- Settlement and ledger (DESIGN.md §6). The platform never holds money:
-- every row here is either an obligation between two travellers (an expense
-- one paid for others) or their record that a debt was settled between them
-- over UPI, outside the platform.
--
-- Amounts are whole paise in BIGINT. Integer arithmetic has no rounding
-- step, so a split that adds up in the application adds up here, and a sum
-- over the ledger is exact whatever its size.
--
-- Sign convention: an account (a user, within one trip) is DEBITED with what
-- they paid out for others and CREDITED with their share of it. A user's net
-- balance on a trip is SUM(debits) - SUM(credits): positive means the others
-- owe them, negative means they owe. A confirmed settlement from A to B
-- debits A (they paid) and credits B (they were paid back).

-- One row per trip that has a ledger. Every write to a trip's ledger and
-- settlements first locks this row, so two writes to one trip queue up and
-- each sees the state the previous one left.
CREATE TABLE trip_ledgers (
    trip_id     UUID PRIMARY KEY REFERENCES trips (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE settlements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id         UUID NOT NULL REFERENCES trip_ledgers (trip_id),
    from_user_id    UUID NOT NULL REFERENCES users (id),
    to_user_id      UUID NOT NULL REFERENCES users (id),
    amount_paise    BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'INR',
    status          VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    marked_paid_at  TIMESTAMPTZ,
    confirmed_at    TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    -- Optimistic lock: any two changes of state that read the same version
    -- cannot both commit.
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_settlements_parties CHECK (to_user_id <> from_user_id),
    CONSTRAINT ck_settlements_amount CHECK (amount_paise > 0),
    CONSTRAINT ck_settlements_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_settlements_status
        CHECK (status IN ('PROPOSED', 'MARKED_PAID', 'CONFIRMED', 'DISPUTED', 'VOIDED')),
    CONSTRAINT ck_settlements_marked_paid
        CHECK (status NOT IN ('MARKED_PAID', 'CONFIRMED') OR marked_paid_at IS NOT NULL),
    CONSTRAINT ck_settlements_confirmed CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CONSTRAINT ck_settlements_closed CHECK ((status IN ('DISPUTED', 'VOIDED')) = (closed_at IS NOT NULL))
);

-- The settle-up screen reads every settlement of one trip.
CREATE INDEX idx_settlements_trip ON settlements (trip_id, created_at);

-- "Settlements I need to act on" differs by role: a payer acts on PROPOSED
-- (mark paid), a payee on MARKED_PAID (confirm).
CREATE INDEX idx_settlements_pending_by_payer ON settlements (from_user_id, status)
    WHERE status IN ('PROPOSED', 'MARKED_PAID');
CREATE INDEX idx_settlements_pending_by_payee ON settlements (to_user_id, status)
    WHERE status = 'MARKED_PAID';

-- APPEND-ONLY. Every change of a settlement's state, with who made it.
CREATE TABLE settlement_state_transitions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id  UUID NOT NULL REFERENCES settlements (id),
    from_status    VARCHAR(20),
    to_status      VARCHAR(20) NOT NULL,
    reason         VARCHAR(50) NOT NULL,
    actor_id       UUID REFERENCES users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_settlement_transitions_reason
        CHECK (reason IN ('PLANNED', 'MARKED_PAID', 'CONFIRMED', 'DISPUTED', 'PLAN_REPLACED')),
    -- Only the planner creates or replaces a settlement; every other change
    -- is made by one of its two people.
    CONSTRAINT ck_settlement_transitions_actor
        CHECK ((reason IN ('PLANNED', 'PLAN_REPLACED')) = (actor_id IS NULL))
);

CREATE INDEX idx_settlement_transitions_settlement ON settlement_state_transitions (settlement_id, created_at);

-- APPEND-ONLY. One double-entry transaction: an expense, the reversal that
-- corrects one, or a confirmed settlement. Its legs are ledger_entries rows.
CREATE TABLE ledger_transactions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id              UUID NOT NULL REFERENCES trip_ledgers (trip_id),
    kind                 VARCHAR(20) NOT NULL,
    amount_paise         BIGINT NOT NULL,
    currency             VARCHAR(3) NOT NULL DEFAULT 'INR',
    description          VARCHAR(120),
    paid_by_user_id      UUID REFERENCES users (id),
    recorded_by_user_id  UUID NOT NULL REFERENCES users (id),
    -- A correction never edits the expense: it is a second transaction with
    -- every leg turned round. UNIQUE, so an expense is reversed at most once.
    reverses_id          UUID UNIQUE REFERENCES ledger_transactions (id),
    -- UNIQUE, so a settlement can reach the ledger once, however many
    -- confirmations race for it.
    settlement_id        UUID UNIQUE REFERENCES settlements (id),
    -- Chosen by the client for an expense (its clientExpenseId), derived by
    -- the server otherwise. Scoped to whoever recorded it, so nobody can
    -- collide with, or probe for, another user's keys.
    idempotency_key      VARCHAR(100) NOT NULL,
    -- SHA-256 of what was asked for, so a retried key with a different
    -- request is refused instead of answered with the first one's result.
    request_fingerprint  VARCHAR(64) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_ledger_transactions_id_trip UNIQUE (id, trip_id),
    CONSTRAINT uq_ledger_transactions_idempotency UNIQUE (recorded_by_user_id, idempotency_key),
    CONSTRAINT ck_ledger_transactions_kind CHECK (kind IN ('EXPENSE', 'EXPENSE_REVERSAL', 'SETTLEMENT')),
    CONSTRAINT ck_ledger_transactions_amount CHECK (amount_paise > 0),
    -- One expense is at most ten lakh rupees.
    CONSTRAINT ck_ledger_transactions_expense_cap CHECK (kind = 'SETTLEMENT' OR amount_paise <= 100000000),
    CONSTRAINT ck_ledger_transactions_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_ledger_transactions_shape CHECK (
        (kind = 'EXPENSE' AND description IS NOT NULL AND paid_by_user_id IS NOT NULL
            AND reverses_id IS NULL AND settlement_id IS NULL)
        OR (kind = 'EXPENSE_REVERSAL' AND reverses_id IS NOT NULL AND settlement_id IS NULL)
        OR (kind = 'SETTLEMENT' AND settlement_id IS NOT NULL AND reverses_id IS NULL)),
    CONSTRAINT ck_ledger_transactions_description
        CHECK (description IS NULL OR char_length(btrim(description)) BETWEEN 1 AND 120)
);

-- The history screen: one trip's transactions, newest first.
CREATE INDEX idx_ledger_transactions_trip_time ON ledger_transactions (trip_id, created_at DESC);

-- APPEND-ONLY, no UPDATE/DELETE. One leg of a transaction.
CREATE TABLE ledger_entries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id   UUID NOT NULL,
    trip_id          UUID NOT NULL,
    -- A user ID, not a wallet: there is no balance here anyone can withdraw.
    account_user_id  UUID NOT NULL REFERENCES users (id),
    entry_type       VARCHAR(6) NOT NULL,
    amount_paise     BIGINT NOT NULL,
    currency         VARCHAR(3) NOT NULL DEFAULT 'INR',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The leg's trip is its transaction's trip, by key, not by trust.
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id, trip_id)
        REFERENCES ledger_transactions (id, trip_id),
    CONSTRAINT ck_ledger_entries_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount CHECK (amount_paise > 0),
    CONSTRAINT ck_ledger_entries_currency CHECK (currency = 'INR')
);

-- Balances: every leg of one trip, per account.
CREATE INDEX idx_ledger_entries_trip_account ON ledger_entries (trip_id, account_user_id);

-- "My settled-obligation history across every trip".
CREATE INDEX idx_ledger_account_time ON ledger_entries (account_user_id, created_at);

-- Checking that a transaction's legs balance groups by this.
CREATE INDEX idx_ledger_transaction ON ledger_entries (transaction_id);

-- APPEND-ONLY. A settlement left undone long enough to count as ghosting
-- it: once per trip, person and role, so the trust score hears it once.
CREATE TABLE settlement_overdue_notices (
    trip_id     UUID NOT NULL REFERENCES trip_ledgers (trip_id),
    user_id     UUID NOT NULL REFERENCES users (id),
    role        VARCHAR(10) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_settlement_overdue_notices PRIMARY KEY (trip_id, user_id, role),
    CONSTRAINT ck_settlement_overdue_notices_role CHECK (role IN ('PAYER', 'PAYEE'))
);

-- Double entry, held by the database: when the inserting transaction
-- commits, every ledger transaction it touched has at least two legs, its
-- debits equal its credits, and both equal its stated amount. Deferred to
-- commit, because the legs are inserted one row at a time.
CREATE FUNCTION ledger_transaction_must_balance() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    checked_id  UUID;
    stated      BIGINT;
    debits      BIGINT;
    credits     BIGINT;
    legs        INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'ledger_entries' THEN
        checked_id := NEW.transaction_id;
    ELSE
        checked_id := NEW.id;
    END IF;
    SELECT amount_paise INTO stated FROM ledger_transactions WHERE id = checked_id;
    SELECT coalesce(sum(amount_paise) FILTER (WHERE entry_type = 'DEBIT'), 0),
           coalesce(sum(amount_paise) FILTER (WHERE entry_type = 'CREDIT'), 0),
           count(*)
    INTO debits, credits, legs
    FROM ledger_entries
    WHERE transaction_id = checked_id;
    IF legs < 2 OR debits <> credits OR debits <> stated THEN
        RAISE EXCEPTION 'ledger transaction % does not balance', checked_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transaction_must_balance();

CREATE CONSTRAINT TRIGGER trg_ledger_transactions_balance
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transaction_must_balance();

-- Append-only, held by the database (DESIGN.md, Append-only enforcement).
-- The application connects as the role that owns these tables, and an
-- owner keeps every privilege a REVOKE could take away, so the refusal is a
-- trigger: it fires for every role, owner and superuser included, and the
-- only way past it is DDL that drops it, which a migration would show.
CREATE FUNCTION refuse_append_only_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

CREATE TRIGGER trg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_ledger_transactions_no_truncate
    BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

CREATE TRIGGER trg_settlement_transitions_append_only
    BEFORE UPDATE OR DELETE ON settlement_state_transitions
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_settlement_transitions_no_truncate
    BEFORE TRUNCATE ON settlement_state_transitions
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

CREATE TRIGGER trg_settlement_overdue_notices_append_only
    BEFORE UPDATE OR DELETE ON settlement_overdue_notices
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_settlement_overdue_notices_no_truncate
    BEFORE TRUNCATE ON settlement_overdue_notices
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

-- The grant layer as well, for when the application connects as a role
-- other than the owner: nobody but the owner may change history.
REVOKE UPDATE, DELETE, TRUNCATE ON ledger_entries, ledger_transactions, settlement_state_transitions,
    settlement_overdue_notices FROM PUBLIC;
