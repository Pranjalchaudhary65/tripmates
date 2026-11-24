-- The public meet-up protocol from DESIGN.md §5: the curated venue
-- allowlist, the mock video-call confirmation that gates it, the meet-ups
-- themselves, each person's arrival, and the private comfort rating.

CREATE TABLE verified_venues (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    address     TEXT NOT NULL,
    city        TEXT NOT NULL,
    category    VARCHAR(30) NOT NULL,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    -- Derived, as for trips, so the two can never disagree.
    point       GEOGRAPHY(POINT, 4326) NOT NULL
        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED,
    -- The venue's local calendar decides which day a meet-up falls on.
    time_zone   VARCHAR(40) NOT NULL DEFAULT 'Asia/Kolkata',
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_verified_venues_category
        CHECK (category IN ('CAFE', 'MALL', 'RESTAURANT', 'PUBLIC_LANDMARK', 'TRANSIT_HUB')),
    CONSTRAINT ck_verified_venues_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_verified_venues_longitude CHECK (longitude BETWEEN -180 AND 180)
);

-- "Venues near this point", a radius query like trip browsing.
CREATE INDEX idx_verified_venues_gist ON verified_venues USING GIST (point);

-- Both people confirm the in-app video call; trip_members.video_verified_at
-- is set once both have.
CREATE TABLE video_call_confirmations (
    member_id               UUID PRIMARY KEY REFERENCES trip_members (id),
    host_confirmed_at       TIMESTAMPTZ,
    companion_confirmed_at  TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE meetups (
    id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id                        UUID NOT NULL REFERENCES trips (id),
    member_id                      UUID NOT NULL REFERENCES trip_members (id),
    host_user_id                   UUID NOT NULL REFERENCES users (id),
    requester_user_id              UUID NOT NULL REFERENCES users (id),
    proposed_by_user_id            UUID NOT NULL REFERENCES users (id),
    -- The allowlist is enforced by this key, not by an API check alone.
    venue_id                       UUID NOT NULL REFERENCES verified_venues (id),
    -- Copied at proposal, so the geofence check needs no join and a venue
    -- edited later does not move a meet-up already arranged.
    venue_name                     TEXT NOT NULL,
    venue_latitude                 DOUBLE PRECISION NOT NULL,
    venue_longitude                DOUBLE PRECISION NOT NULL,
    venue_point                    GEOGRAPHY(POINT, 4326) NOT NULL
        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(venue_longitude, venue_latitude), 4326)::geography) STORED,
    scheduled_at                   TIMESTAMPTZ NOT NULL,
    status                         VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    host_plus_one                  BOOLEAN NOT NULL DEFAULT FALSE,
    requester_plus_one             BOOLEAN NOT NULL DEFAULT FALSE,
    cancellation_type              VARCHAR(20),
    -- Kept for the safety team; never returned to the other person.
    cancelled_by_user_id           UUID REFERENCES users (id),
    emergency_contact_notified_at  TIMESTAMPTZ,
    completed_at                   TIMESTAMPTZ,
    -- A confirm and a cancel sent together cannot both commit.
    version                        BIGINT NOT NULL DEFAULT 0,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_meetups_status CHECK (status IN ('PROPOSED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT ck_meetups_two_people CHECK (host_user_id <> requester_user_id),
    CONSTRAINT ck_meetups_proposer CHECK (proposed_by_user_id IN (host_user_id, requester_user_id)),
    CONSTRAINT ck_meetups_cancellation
        CHECK ((status = 'CANCELLED') = (cancellation_type IS NOT NULL)
            AND (cancellation_type IS NULL OR cancellation_type IN ('CONVENIENCE', 'SAFETY'))),
    CONSTRAINT ck_meetups_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

-- At most one meet-up being arranged per match. A cancelled or missed one
-- stays as history, and a new one can be proposed after it.
CREATE UNIQUE INDEX uq_meetups_active ON meetups (member_id) WHERE status IN ('PROPOSED', 'CONFIRMED');

-- The safety job scans confirmed meet-ups by time; nothing else is in it.
CREATE INDEX idx_meetups_confirmed_schedule ON meetups (scheduled_at) WHERE status = 'CONFIRMED';

CREATE INDEX idx_meetups_member ON meetups (member_id);

-- One row per person per confirmed meet-up, created when it is confirmed, so
-- the safety job can see who has not arrived.
CREATE TABLE meetup_checkins (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meetup_id            UUID NOT NULL REFERENCES meetups (id),
    user_id              UUID NOT NULL REFERENCES users (id),
    arrived_at           TIMESTAMPTZ,
    -- Within 100 m of a public venue, by construction; used only for the
    -- geofence and never returned to the other person.
    arrival_latitude     DOUBLE PRECISION,
    arrival_longitude    DOUBLE PRECISION,
    distance_meters      NUMERIC(7, 1),
    selfie_key           TEXT,
    selfie_purge_due_at  TIMESTAMPTZ,
    safe_confirmed_at    TIMESTAMPTZ,
    pinged_at            TIMESTAMPTZ,
    escalated_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_meetup_checkins_meetup_user UNIQUE (meetup_id, user_id),
    -- Arrival needs both signals: a point inside the geofence and a selfie.
    CONSTRAINT ck_meetup_checkins_arrival CHECK ((arrived_at IS NULL) = (arrival_latitude IS NULL)
        AND (arrived_at IS NULL) = (arrival_longitude IS NULL)
        AND (arrived_at IS NULL) = (distance_meters IS NULL)),
    CONSTRAINT ck_meetup_checkins_selfie_purge CHECK (selfie_key IS NULL OR selfie_purge_due_at IS NOT NULL),
    CONSTRAINT ck_meetup_checkins_geofence CHECK (distance_meters IS NULL OR distance_meters <= 100)
);

-- The purge job: stored selfies by due time.
CREATE INDEX idx_meetup_checkins_purge ON meetup_checkins (selfie_purge_due_at) WHERE selfie_key IS NOT NULL;

-- The private comfort rating. Only the meet-up module reads this table:
-- it is never joined into reviews, trust or any profile view.
CREATE TABLE meetup_feedback (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meetup_id         UUID NOT NULL REFERENCES meetups (id),
    user_id           UUID NOT NULL REFERENCES users (id),
    comfort_rating    SMALLINT NOT NULL,
    -- Sealed under the author's data key.
    notes_ciphertext  BYTEA,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_meetup_feedback_meetup_user UNIQUE (meetup_id, user_id),
    CONSTRAINT ck_meetup_feedback_rating CHECK (comfort_rating BETWEEN 1 AND 5)
);

-- The seeded allowlist: busy, public, staffed places in the cities the place
-- picker offers. Coordinates are each venue's public location.
INSERT INTO verified_venues (name, address, city, category, latitude, longitude) VALUES
    ('Phoenix Marketcity, central atrium', 'Whitefield Main Road, Mahadevapura', 'Bengaluru', 'MALL', 12.9975, 77.6966),
    ('Bengaluru City Railway Station, main concourse', 'Gubbi Thotadappa Road, Majestic', 'Bengaluru', 'TRANSIT_HUB', 12.9767, 77.5713),
    ('Cubbon Park, Bal Bhavan gate', 'Kasturba Road', 'Bengaluru', 'PUBLIC_LANDMARK', 12.9763, 77.5929),
    ('Chowrasta Mall Road square', 'The Mall, Chowrasta', 'Darjeeling', 'PUBLIC_LANDMARK', 27.0428, 88.2663),
    ('Darjeeling Railway Station', 'Hill Cart Road', 'Darjeeling', 'TRANSIT_HUB', 27.0367, 88.2627),
    ('Rajiv Chowk Metro Station, Gate 7', 'Connaught Place', 'Delhi', 'TRANSIT_HUB', 28.6328, 77.2197),
    ('Select Citywalk, ground floor atrium', 'Saket District Centre', 'Delhi', 'MALL', 28.5286, 77.2190),
    ('India Gate lawns, Rajpath side', 'Kartavya Path', 'Delhi', 'PUBLIC_LANDMARK', 28.6129, 77.2295),
    ('Mall De Goa, main entrance', 'NH 66, Porvorim', 'Goa', 'MALL', 15.5368, 73.8139),
    ('Madgaon Railway Station', 'Station Road, Margao', 'Goa', 'TRANSIT_HUB', 15.2662, 73.9718),
    ('Panjim Church square', 'Emidio Gracia Road, Panaji', 'Goa', 'PUBLIC_LANDMARK', 15.4989, 73.8278),
    ('Gokarna bus stand', 'Car Street', 'Gokarna', 'TRANSIT_HUB', 14.5402, 74.3167),
    ('Mahabaleshwar Temple forecourt', 'Car Street', 'Gokarna', 'PUBLIC_LANDMARK', 14.5420, 74.3168),
    ('Hampi Bazaar, Virupaksha Temple entrance', 'Hampi Bazaar Road', 'Hampi', 'PUBLIC_LANDMARK', 15.3350, 76.4600),
    ('Hosapete Junction railway station', 'Station Road, Hosapete', 'Hampi', 'TRANSIT_HUB', 15.2693, 76.3871),
    ('Jaipur Junction railway station', 'Station Road, Gopalbari', 'Jaipur', 'TRANSIT_HUB', 26.9196, 75.7878),
    ('World Trade Park, central court', 'Jawaharlal Nehru Marg, Malviya Nagar', 'Jaipur', 'MALL', 26.8535, 75.8048),
    ('Hawa Mahal, front plaza', 'Hawa Mahal Road, Badi Choupad', 'Jaipur', 'PUBLIC_LANDMARK', 26.9239, 75.8267),
    ('Leh Main Bazaar, polo ground end', 'Main Bazaar Road', 'Leh', 'PUBLIC_LANDMARK', 34.1642, 77.5848),
    ('Manali Mall Road, Hidimba Chowk', 'The Mall', 'Manali', 'PUBLIC_LANDMARK', 32.2432, 77.1892),
    ('Manali bus stand', 'Mall Road', 'Manali', 'TRANSIT_HUB', 32.2445, 77.1893),
    ('Chhatrapati Shivaji Maharaj Terminus, main hall', 'Dr Dadabhai Naoroji Road, Fort', 'Mumbai', 'TRANSIT_HUB', 18.9398, 72.8355),
    ('Phoenix Palladium, central atrium', 'Senapati Bapat Marg, Lower Parel', 'Mumbai', 'MALL', 18.9947, 72.8256),
    ('Gateway of India, promenade', 'Apollo Bandar, Colaba', 'Mumbai', 'PUBLIC_LANDMARK', 18.9220, 72.8347),
    ('Munnar town bus stand', 'Main Bazaar', 'Munnar', 'TRANSIT_HUB', 10.0889, 77.0595),
    ('Promenade Beach, Gandhi statue', 'Goubert Avenue', 'Puducherry', 'PUBLIC_LANDMARK', 11.9327, 79.8356),
    ('Puducherry bus stand', 'Maraimalai Adigal Salai', 'Puducherry', 'TRANSIT_HUB', 11.9339, 79.8188),
    ('Triveni Ghat, aarti steps', 'Triveni Ghat Road', 'Rishikesh', 'PUBLIC_LANDMARK', 30.1031, 78.2961),
    ('Rishikesh ISBT', 'Haridwar Road', 'Rishikesh', 'TRANSIT_HUB', 30.0952, 78.2896),
    ('Udaipur City railway station', 'Station Road', 'Udaipur', 'TRANSIT_HUB', 24.5734, 73.7066),
    ('Gangaur Ghat, Lake Pichola', 'Gangaur Ghat Marg', 'Udaipur', 'PUBLIC_LANDMARK', 24.5794, 73.6830),
    ('Dashashwamedh Ghat, main steps', 'Dashashwamedh Ghat Road', 'Varanasi', 'PUBLIC_LANDMARK', 25.3069, 83.0105),
    ('Varanasi Junction railway station', 'Station Road, Chetganj', 'Varanasi', 'TRANSIT_HUB', 25.3270, 82.9876);
