-- The match score cache from DESIGN.md §3: one viewer's soft score against
-- one trip, with the reasons behind it.
--
-- Deal-breakers are not cached: they are checked live on every read, so a
-- changed limit, gender or trust score takes effect at once. What is cached
-- is only the weighted comparison of two vibe profiles. Each row records the
-- updated_at of both profiles it was computed from and the scorer that
-- computed it; a row whose stamps or scorer no longer match is stale and is
-- never served. A profile change therefore invalidates every cached score
-- involving it the moment it commits, with no event having to arrive first,
-- and a change to the scoring rules invalidates every row at once.

CREATE TABLE match_score_cache (
    trip_id            UUID NOT NULL REFERENCES trips (id),
    user_id            UUID NOT NULL REFERENCES users (id),
    score              SMALLINT NOT NULL,
    explanation        JSONB NOT NULL,
    scorer             VARCHAR(40) NOT NULL,
    viewer_profile_at  TIMESTAMPTZ NOT NULL,
    host_profile_at    TIMESTAMPTZ NOT NULL,
    computed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_match_score_cache PRIMARY KEY (trip_id, user_id),
    CONSTRAINT ck_match_score_cache_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_match_score_cache_explanation CHECK (jsonb_typeof(explanation) = 'object')
);

-- Dropping every score involving one user goes by user_id alone; the
-- primary key leads with trip_id, so without this it is a full scan.
CREATE INDEX idx_match_score_cache_user ON match_score_cache (user_id);
