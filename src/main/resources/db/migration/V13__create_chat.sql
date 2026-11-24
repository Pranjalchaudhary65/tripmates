-- Guided masked chat from DESIGN.md §4: one conversation per accepted
-- companion and trip, its messages, and the moderation flags raised on them.

CREATE TABLE conversations (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id              UUID NOT NULL REFERENCES trips (id),
    host_user_id         UUID NOT NULL REFERENCES users (id),
    requester_user_id    UUID NOT NULL REFERENCES users (id),
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- Set when the trip locks for this companion; null keeps contact
    -- details behind the masked chat.
    contact_unlocked_at  TIMESTAMPTZ,
    -- Messages are numbered 1, 2, 3... within a conversation. The next number
    -- is taken under this row's lock, so two messages sent together can never
    -- get the same one, and a client can see a gap and refetch.
    last_seq             BIGINT NOT NULL DEFAULT 0,
    -- The highest message number each side has read; unread counts are the
    -- other side's messages above it.
    host_read_seq        BIGINT NOT NULL DEFAULT 0,
    requester_read_seq   BIGINT NOT NULL DEFAULT 0,
    last_message_at      TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_conversations_trip_requester UNIQUE (trip_id, requester_user_id),
    CONSTRAINT ck_conversations_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_conversations_two_people CHECK (host_user_id <> requester_user_id),
    CONSTRAINT ck_conversations_last_seq CHECK (last_seq >= 0),
    CONSTRAINT ck_conversations_read_seq
        CHECK (host_read_seq BETWEEN 0 AND last_seq AND requester_read_seq BETWEEN 0 AND last_seq)
);

-- A host's inbox lists every conversation on the trips they host; a
-- companion's lists theirs.
CREATE INDEX idx_conversations_host ON conversations (host_user_id);
CREATE INDEX idx_conversations_requester ON conversations (requester_user_id);

CREATE TABLE chat_messages (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id    UUID NOT NULL REFERENCES conversations (id),
    seq                BIGINT NOT NULL,
    kind               VARCHAR(10) NOT NULL,
    -- Null for a SYSTEM message, which no person wrote.
    sender_id          UUID REFERENCES users (id),
    -- Chosen by the sending client, so a retried send is stored once.
    client_message_id  UUID,
    -- The text after markup was stripped and contact details redacted,
    -- AES-GCM under the sender's data key. The unredacted text is never
    -- stored anywhere.
    body_ciphertext    BYTEA,
    -- A SYSTEM message is a fixed code the client words itself, never text.
    system_code        VARCHAR(30),
    redacted           BOOLEAN NOT NULL DEFAULT FALSE,
    flagged            BOOLEAN NOT NULL DEFAULT FALSE,
    flag_reason        VARCHAR(30),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Also the index behind history pages and unread counts: one
    -- conversation's messages by number.
    CONSTRAINT uq_chat_messages_conversation_seq UNIQUE (conversation_id, seq),
    CONSTRAINT uq_chat_messages_client_id UNIQUE (conversation_id, sender_id, client_message_id),
    CONSTRAINT ck_chat_messages_seq CHECK (seq >= 1),
    CONSTRAINT ck_chat_messages_kind CHECK (kind IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_chat_messages_shape CHECK (
        (kind = 'USER' AND sender_id IS NOT NULL AND client_message_id IS NOT NULL
            AND body_ciphertext IS NOT NULL AND system_code IS NULL)
        OR (kind = 'SYSTEM' AND sender_id IS NULL AND client_message_id IS NULL
            AND body_ciphertext IS NULL AND system_code IS NOT NULL AND NOT flagged AND NOT redacted)),
    CONSTRAINT ck_chat_messages_system_code
        CHECK (system_code IN ('CONTACT_UNLOCKED', 'CONVERSATION_CLOSED')),
    CONSTRAINT ck_chat_messages_flag_reason
        CHECK (flag_reason IN ('OFF_PLATFORM_PUSH', 'MONEY_REQUEST', 'HARASSMENT')),
    CONSTRAINT ck_chat_messages_flagged CHECK (flagged = (flag_reason IS NOT NULL))
);

CREATE TABLE moderation_flags (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_message_id  UUID NOT NULL REFERENCES chat_messages (id),
    flag_type        VARCHAR(30) NOT NULL,
    confidence       NUMERIC(4, 3) NOT NULL,
    auto_action      VARCHAR(30) NOT NULL DEFAULT 'NONE',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_moderation_flags_type CHECK (flag_type IN ('OFF_PLATFORM_PUSH', 'MONEY_REQUEST', 'HARASSMENT')),
    CONSTRAINT ck_moderation_flags_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_moderation_flags_action CHECK (auto_action IN ('NONE', 'TRUST_SCORE_PENALTY', 'AUTO_SUSPEND'))
);

-- A message's flags, for the moderation queue.
CREATE INDEX idx_moderation_flags_message ON moderation_flags (chat_message_id);

-- Companions accepted before chat existed get their conversation too.
INSERT INTO conversations (trip_id, host_user_id, requester_user_id, created_at, updated_at)
SELECT m.trip_id, t.host_user_id, m.user_id, m.decided_at, m.decided_at
FROM trip_members m
JOIN trips t ON t.id = m.trip_id
WHERE m.status IN ('ACCEPTED', 'LOCKED');
