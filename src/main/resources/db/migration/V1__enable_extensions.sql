-- pgcrypto: gen_random_uuid() backs every UUID primary key in this schema.
-- citext: case-insensitive email column (DESIGN.md users.email) without a
--   LOWER(email) index workaround.
-- postgis: GEOGRAPHY(POINT, 4326) columns + GIST indexes for trip/venue
--   radius queries, per DESIGN.md - not used by any table yet, enabled up
--   front so later schema migrations never have to revisit this one.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS postgis;
