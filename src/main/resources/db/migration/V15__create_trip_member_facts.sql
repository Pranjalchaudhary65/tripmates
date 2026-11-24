-- Who is on which trip, published by trips as a read-only view for other
-- modules' SQL (DESIGN.md, Module boundaries). member_id is the "match" the
-- meet-up screens are addressed by; the chat reads it to link there.

CREATE VIEW trip_member_facts AS
SELECT id AS member_id,
       trip_id,
       user_id,
       status
FROM trip_members;
