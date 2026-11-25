-- Live trip mode and safety (DESIGN.md §7): a traveller shares their trip
-- with their emergency contact and answers regular "are you safe?"
-- check-ins; a missed one escalates to that contact, and SOS reaches them at
-- once. The emergency contact itself is identity's (emergency_contacts): one
-- record, reused by the meet-up and here, never copied.
--
-- Safety actions are always free: nothing here is ever a trust score input.
-- An SOS is also a moderation flag, through the outbox.

-- One traveller's live-trip sharing on one trip.
CREATE TABLE itinerary_shares (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id               UUID NOT NULL REFERENCES trips (id),
    user_id               UUID NOT NULL REFERENCES users (id),
    check_in_every_hours  SMALLINT NOT NULL,
    -- Where they are staying, who to ask for: their own words, AES-GCM
    -- under their key, sent only to their emergency contact in an alert.
    note_ciphertext       BYTEA,
    started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    stopped_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_itinerary_shares_trip_user UNIQUE (trip_id, user_id),
    CONSTRAINT ck_itinerary_shares_interval CHECK (check_in_every_hours IN (3, 6, 12, 24))
);

CREATE TABLE checkins (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id              UUID NOT NULL REFERENCES trips (id),
    user_id              UUID NOT NULL REFERENCES users (id),
    scheduled_at         TIMESTAMPTZ NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    responded_at         TIMESTAMPTZ,
    pinged_at            TIMESTAMPTZ,
    escalated_at         TIMESTAMPTZ,
    -- Rounded to two decimal places (about a kilometre) before it is
    -- written; the exact point exists only encrypted, for an alert. The
    -- geography point is derived by the database, as for trips.
    rounded_latitude     DOUBLE PRECISION,
    rounded_longitude    DOUBLE PRECISION,
    location_point       GEOGRAPHY(POINT, 4326)
        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(rounded_longitude, rounded_latitude), 4326)::geography) STORED,
    location_ciphertext  BYTEA,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_checkins_status CHECK (status IN ('PENDING', 'CONFIRMED', 'MISSED', 'CANCELLED')),
    CONSTRAINT ck_checkins_confirmed CHECK ((status = 'CONFIRMED') = (responded_at IS NOT NULL)),
    CONSTRAINT ck_checkins_location CHECK ((rounded_latitude IS NULL) = (location_ciphertext IS NULL)
        AND (rounded_latitude IS NULL) = (rounded_longitude IS NULL)),
    -- The clear point can never be more precise than about a kilometre.
    CONSTRAINT ck_checkins_rounded CHECK (rounded_latitude IS NULL OR (
        rounded_latitude = round(rounded_latitude::numeric, 2) AND rounded_longitude = round(rounded_longitude::numeric, 2)))
);

-- The safety job's only scan: pending check-ins that have come due.
CREATE INDEX idx_checkins_pending_due ON checkins (scheduled_at) WHERE status = 'PENDING';

-- At most one check-in waiting per person per trip.
CREATE UNIQUE INDEX uq_checkins_one_pending ON checkins (trip_id, user_id) WHERE status = 'PENDING';

-- A traveller's latest answered check-in, for the live screen.
CREATE INDEX idx_checkins_trip_user_time ON checkins (trip_id, user_id, scheduled_at DESC);

CREATE TABLE sos_events (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id                         UUID NOT NULL REFERENCES trips (id),
    user_id                         UUID NOT NULL REFERENCES users (id),
    -- Chosen by the client once per press, so a retry on a bad network
    -- raises one SOS, not several.
    client_sos_id                   UUID NOT NULL,
    triggered_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    rounded_latitude                DOUBLE PRECISION,
    rounded_longitude               DOUBLE PRECISION,
    last_known_point                GEOGRAPHY(POINT, 4326)
        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(rounded_longitude, rounded_latitude), 4326)::geography) STORED,
    last_known_location_ciphertext  BYTEA,
    status                          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    escalated_at                    TIMESTAMPTZ,
    alert_attempts                  SMALLINT NOT NULL DEFAULT 0,
    resolved_at                     TIMESTAMPTZ,
    resolution_notes                TEXT,
    version                         BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_sos_events_client UNIQUE (user_id, client_sos_id),
    CONSTRAINT ck_sos_events_status CHECK (status IN ('OPEN', 'ESCALATED', 'RESOLVED')),
    CONSTRAINT ck_sos_events_escalated CHECK (status <> 'ESCALATED' OR escalated_at IS NOT NULL),
    CONSTRAINT ck_sos_events_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT ck_sos_events_location CHECK ((rounded_latitude IS NULL) = (last_known_location_ciphertext IS NULL)
        AND (rounded_latitude IS NULL) = (rounded_longitude IS NULL)),
    CONSTRAINT ck_sos_events_rounded CHECK (rounded_latitude IS NULL OR (
        rounded_latitude = round(rounded_latitude::numeric, 2) AND rounded_longitude = round(rounded_longitude::numeric, 2))),
    CONSTRAINT ck_sos_events_attempts CHECK (alert_attempts >= 0)
);

-- The on-call safety dashboard, and the job retrying undelivered alerts.
CREATE INDEX idx_sos_open ON sos_events (status, triggered_at) WHERE status <> 'RESOLVED';

-- A user's open SOS on a trip, for the live screen.
CREATE INDEX idx_sos_trip_user ON sos_events (trip_id, user_id, triggered_at DESC);
