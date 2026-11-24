-- Trips and membership from DESIGN.md §3: trips and trip_members. The match
-- score cache arrives with matching.

CREATE TABLE trips (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    host_user_id           UUID NOT NULL REFERENCES users (id),
    title                  VARCHAR(140) NOT NULL,
    description            TEXT NOT NULL,
    destination_name       TEXT NOT NULL,
    -- The application reads and writes the coordinates as plain numbers;
    -- the geography point is derived from them by the database, so the two
    -- can never disagree and the entity needs no spatial type.
    destination_latitude   DOUBLE PRECISION NOT NULL,
    destination_longitude  DOUBLE PRECISION NOT NULL,
    destination_point      GEOGRAPHY(POINT, 4326) NOT NULL
        GENERATED ALWAYS AS (
            ST_SetSRID(ST_MakePoint(destination_longitude, destination_latitude), 4326)::geography) STORED,
    destination_radius_km  NUMERIC(6, 2) NOT NULL DEFAULT 0,
    start_date             DATE NOT NULL,
    end_date               DATE NOT NULL,
    total_slots            SMALLINT NOT NULL,
    filled_slots           SMALLINT NOT NULL DEFAULT 0,
    budget_band            VARCHAR(20) NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    -- Optimistic lock: every slot claim bumps it, so two claims that read
    -- the same row cannot both commit.
    version                BIGINT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_trips_title CHECK (char_length(btrim(title)) >= 3),
    CONSTRAINT ck_trips_latitude CHECK (destination_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_trips_longitude CHECK (destination_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_trips_radius CHECK (destination_radius_km BETWEEN 0 AND 500),
    CONSTRAINT ck_trips_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_trips_total_slots CHECK (total_slots BETWEEN 1 AND 20),
    -- The last line of defence against an overfilled trip, whatever the
    -- application gets wrong.
    CONSTRAINT ck_trips_filled_slots CHECK (filled_slots BETWEEN 0 AND total_slots),
    CONSTRAINT ck_trips_budget_band CHECK (budget_band IN ('SHOESTRING', 'MID', 'COMFORT', 'LUXURY')),
    CONSTRAINT ck_trips_status
        CHECK (status IN ('DRAFT', 'OPEN', 'FULL', 'LOCKED', 'COMPLETED', 'CANCELLED')),
    -- OPEN and FULL say whether a slot is left; they must agree with the count.
    CONSTRAINT ck_trips_open_has_room CHECK (status <> 'OPEN' OR filled_slots < total_slots),
    CONSTRAINT ck_trips_full_has_none CHECK (status <> 'FULL' OR filled_slots = total_slots)
);

-- Radius browsing (ST_DWithin) needs a spatial index; a B-tree cannot
-- answer a distance query on a geography.
CREATE INDEX idx_trips_destination_gist ON trips USING GIST (destination_point);

-- The browse page's default: open trips, soonest first. Locked, completed
-- and cancelled trips pile up as the table ages and are left out.
CREATE INDEX idx_trips_status_startdate ON trips (status, start_date) WHERE status IN ('OPEN', 'FULL');

-- A host's own trips, and the per-host cap on open ones.
CREATE INDEX idx_trips_host_status ON trips (host_user_id, status);

CREATE TABLE trip_members (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id              UUID NOT NULL REFERENCES trips (id),
    user_id              UUID NOT NULL REFERENCES users (id),
    status               VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    video_verified_at    TIMESTAMPTZ,
    cancellation_type    VARCHAR(20),
    cancellation_reason  TEXT,
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at           TIMESTAMPTZ,
    locked_at            TIMESTAMPTZ,
    -- Optimistic lock on the request itself, so an accept and a reject of
    -- the same request cannot both commit.
    version              BIGINT NOT NULL DEFAULT 0,

    -- One request per person per trip, ever: a rejected request cannot be
    -- sent again to wear the host down.
    CONSTRAINT uq_trip_members_trip_user UNIQUE (trip_id, user_id),
    CONSTRAINT ck_trip_members_status
        CHECK (status IN ('REQUESTED', 'ACCEPTED', 'REJECTED', 'LOCKED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT ck_trip_members_cancellation_type
        CHECK (cancellation_type IN ('CONVENIENCE', 'SAFETY')),
    CONSTRAINT ck_trip_members_decided CHECK (status = 'REQUESTED' OR decided_at IS NOT NULL)
);

-- "My requests": one user's memberships by status, across every trip.
CREATE INDEX idx_trip_members_user_status ON trip_members (user_id, status);

-- A host's inbox: the requests on their trips, by status.
CREATE INDEX idx_trip_members_trip_status ON trip_members (trip_id, status);
