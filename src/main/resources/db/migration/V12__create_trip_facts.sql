-- The facts about a trip that other modules may read in SQL, published by
-- trips as a view, the same contract identity keeps with user_public_facts
-- (DESIGN.md, Module boundaries). Chat and meet-ups read a trip's title,
-- place and dates through it, never the trips table itself.

CREATE VIEW trip_facts AS
SELECT id AS trip_id,
       host_user_id,
       title,
       destination_name,
       destination_latitude,
       destination_longitude,
       destination_point,
       start_date,
       end_date,
       budget_band,
       status
FROM trips;
