-- The deal-breakers matching applies in SQL, published by vibe as a view so
-- the trips module filters on them without touching vibe's tables
-- (DESIGN.md, Module boundaries). A user who never set any has no row here;
-- readers treat that as no limits at all (EITHER, EITHER, ANY, 0), the same
-- defaults the deal-breakers form starts from.

CREATE VIEW deal_breaker_facts AS
SELECT user_id,
       smoking,
       drinking,
       gender_preference,
       min_trust_score
FROM deal_breakers;
