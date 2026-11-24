-- The one person to alert when a user goes quiet at a meet-up, and later on
-- a trip (DESIGN.md §5 and §7). Owned by identity: it is part of the user's
-- own profile, and every module that needs it asks identity rather than
-- keeping its own copy. The phone number is AES-GCM under the user's data
-- key, like the user's own number.

CREATE TABLE emergency_contacts (
    user_id           UUID PRIMARY KEY REFERENCES users (id),
    name              TEXT NOT NULL,
    relationship      VARCHAR(20) NOT NULL,
    phone_ciphertext  BYTEA NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_emergency_contacts_name CHECK (char_length(btrim(name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_emergency_contacts_relationship
        CHECK (relationship IN ('PARENT', 'SIBLING', 'PARTNER', 'RELATIVE', 'FRIEND', 'OTHER'))
);
