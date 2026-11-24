-- The facts about a user that other modules may read in SQL, published by
-- identity as a view so no other module's query touches the users table
-- itself (DESIGN.md, Module boundaries). Nothing secret or contact-like is
-- here: no email, phone, date of birth, full name or photo key.
--
-- first_name is what another traveller sees before the trust ladder has
-- gone anywhere. gender and trust_score are for matching filters only and
-- are never returned to another user.

CREATE VIEW user_public_facts AS
SELECT id                                                 AS user_id,
       split_part(btrim(full_name), ' ', 1)               AS first_name,
       gender,
       id_verification_tier,
       trust_score,
       (account_status = 'ACTIVE' AND deleted_at IS NULL) AS active
FROM users;
