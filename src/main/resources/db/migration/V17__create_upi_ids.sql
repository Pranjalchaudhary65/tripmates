-- The UPI ID a traveller wants to be paid at, self-declared (DESIGN.md §6).
-- Owned by identity, like the emergency contact: part of the user's own
-- profile, which other modules ask for rather than copy. Settle-up builds a
-- upi:// payment link from it for whoever owes this user money; the
-- platform itself never receives or sends a payment.
--
-- Encrypted under the user's data key: a UPI ID is very often a phone number
-- with a bank suffix, so it is contact data, read only by the people who are
-- about to pay its owner.

CREATE TABLE upi_ids (
    user_id         UUID PRIMARY KEY REFERENCES users (id),
    vpa_ciphertext  BYTEA NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
