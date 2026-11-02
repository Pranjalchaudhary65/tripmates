# TripMates — DESIGN.md

This document is the source of truth for the data model, module boundaries,
and the two core state machines (Trust Score, Escrow). It must stay in sync
with the Flyway migrations under `src/main/resources/db/migration`. If code
and this doc disagree, the code is wrong until this doc is updated and the
reason is recorded in a commit message.

Conventions used everywhere below unless stated otherwise:
- Primary keys: `UUID DEFAULT gen_random_uuid()`. Reason: trip/user/escrow
  IDs are exposed in URLs and WebSocket topics; sequential bigints make
  IDOR enumeration trivial ("try id+1"). UUIDs don't fix authorization by
  themselves (we still check ownership on every request) but they remove
  free enumeration as an attack surface.
- Timestamps: `TIMESTAMPTZ`, always UTC.
- Money: `NUMERIC(12,2)` with a fixed currency column (`INR` only for now,
  but we always carry a `currency` column rather than assume one — adding
  multi-currency later must never become a silent precision bug).
- Enums: implemented as `VARCHAR` + `CHECK (... IN (...))`, NOT native
  Postgres `ENUM` types. Reason: adding a new enum value to a native
  Postgres ENUM requires `ALTER TYPE ... ADD VALUE`, which cannot run
  inside a transaction with other DDL in older Postgres and complicates
  Flyway migrations. A CHECK constraint is just as strict and is a normal
  migration to edit.
- Every table that is mutated after creation gets `created_at` and
  `updated_at`. Tables that are **append-only by design** (ledger, audit
  log, state transitions, outbox) deliberately have NO `updated_at` and NO
  `UPDATE` grants in the application's DB role — see "Append-only
  enforcement" below.
- Soft delete: `deleted_at TIMESTAMPTZ NULL` on `users` only. Every other
  table cascades logically through `users.deleted_at`, not its own flag.

## Module boundaries

Each module is a Java package under `com.tripmate.tripmate.<module>`, with
its own `controller / service / repository / dto / entity` sub-packages.
Modules talk to each other only through service interfaces, never by
reaching into another module's repository or entity directly — this is
what makes the authorization tests and the trust-score outbox possible
without circular dependencies.

| Module | Owns tables | Depends on |
|---|---|---|
| `identity` | users, user_encryption_keys, identity_verifications, otp_codes, totp_backup_codes, refresh_token_families, login_history, active_sessions | — (foundation) |
| `vibe` | vibe_profiles, deal_breakers | identity |
| `trips` | trips, trip_members, match_score_cache | identity, vibe |
| `chat` | conversations, chat_messages, moderation_flags | identity, trips |
| `escrow` | wallets, ledger_entries, escrows, escrow_state_transitions, expenses, expense_shares | identity, trips |
| `safety` | itinerary_shares, checkins, sos_events | identity, trips |
| `trust` | reviews, trust_score_events, trust_score_snapshots | identity, trips, escrow, safety |
| `platform` | outbox_events, audit_log, consent_records, data_requests, blocks_reports | all (infra module, no business logic) |

`trust` is deliberately downstream of everything else: it only ever reacts
to events other modules publish to the outbox. No module calls into
`trust` synchronously to "update my score" — see the Trust Score Engine
section.

## Append-only enforcement

`ledger_entries`, `escrow_state_transitions`, `audit_log`, and
`outbox_events` must never be updated or deleted by the application. This
is enforced at two layers, not just by convention:
1. The Spring Data repositories for these tables expose only `save`
   (insert) and `findBy...` — no `deleteById`, no entities with mutable
   setters on persisted fields (constructor-bound, `@Immutable` where JPA
   supports it).
2. The Postgres role the application connects with (`tripmate_app`) is
   granted `INSERT, SELECT` but **not** `UPDATE, DELETE` on these four
   tables, applied via a Flyway migration. This means even a bug (or a
   future developer who ignores #1) cannot silently mutate history — the
   database itself rejects it. A unit test asserts this by attempting an
   `UPDATE` through the app's datasource against a Testcontainers Postgres
   and expecting a permission error.

---

## Schema

### 1. Identity & Auth

```sql
users
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid()
  email                   CITEXT NOT NULL UNIQUE
  password_hash           TEXT NOT NULL                -- Argon2id
  full_name               TEXT NOT NULL
  date_of_birth           DATE NOT NULL
  gender                  VARCHAR(20) NOT NULL CHECK (gender IN ('MALE','FEMALE','NON_BINARY','PREFER_NOT_TO_SAY'))
  phone_ciphertext        BYTEA                         -- AES-GCM envelope-encrypted
  phone_blind_index       VARCHAR(64)                   -- HMAC-SHA256(phone), indexed, for uniqueness/lookup without decrypting
  profile_photo_key       TEXT                          -- object storage key, never a public URL
  email_verified_at       TIMESTAMPTZ
  phone_verified_at       TIMESTAMPTZ
  id_verification_tier    VARCHAR(20) NOT NULL DEFAULT 'NONE'
                            CHECK (id_verification_tier IN ('NONE','EMAIL','PHONE','ID_DOCUMENT','SELFIE_MATCH'))
  totp_secret_ciphertext  BYTEA
  totp_enabled            BOOLEAN NOT NULL DEFAULT FALSE
  trust_score             NUMERIC(5,2) NOT NULL DEFAULT 0   -- denormalized read cache, owned by trust module
  trust_score_updated_at  TIMESTAMPTZ
  account_status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (account_status IN ('ACTIVE','SUSPENDED','BANNED','DELETED'))
  role                    VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN'))
  encryption_key_id       UUID REFERENCES user_encryption_keys(id)
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
  deleted_at              TIMESTAMPTZ

  INDEX idx_users_phone_blind_index (phone_blind_index)
    -- WHY: lets us enforce "one account per phone number" and look up by
    -- phone (e.g. OTP login) with an equality match on an HMAC, without
    -- ever decrypting phone_ciphertext for a read-path lookup.
  INDEX idx_users_account_status (account_status) WHERE account_status <> 'ACTIVE'
    -- WHY: admin moderation queries ("show me suspended users") filter on
    -- this constantly; partial index keeps it tiny since >99% of rows are ACTIVE.
```

```sql
user_encryption_keys
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id       UUID NOT NULL REFERENCES users(id)
  wrapped_dek   BYTEA NOT NULL      -- per-user Data Encryption Key, wrapped by the app's master Key Encryption Key (env/secret manager)
  active        BOOLEAN NOT NULL DEFAULT TRUE
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  revoked_at    TIMESTAMPTZ

  INDEX idx_uek_user_active (user_id) WHERE active
    -- WHY: envelope-decryption of any PII field for a user first fetches
    -- the one active DEK; this index makes that a single-row lookup.
```
This table is the hook for crypto-shredding: GDPR/DPDP-style deletion sets
`active = FALSE, revoked_at = now()` and destroys the unwrapped DEK from
memory/cache. Every ciphertext column encrypted under that DEK becomes
permanently unreadable even though the row still physically exists — which
is what lets us satisfy "delete my data" without breaking foreign keys in
the ledger/audit tables that must legally stay append-only.

```sql
identity_verifications
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id             UUID NOT NULL REFERENCES users(id)
  tier                VARCHAR(20) NOT NULL CHECK (tier IN ('PHONE','ID_DOCUMENT','SELFIE_MATCH'))
  status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED'))
  document_type       VARCHAR(30)                  -- e.g. PASSPORT, DRIVERS_LICENSE; null for SELFIE_MATCH
  document_storage_key TEXT                         -- randomized name, outside web root, short-lived signed URL only
  reviewer_id         UUID REFERENCES users(id)     -- admin who approved/rejected (mock verifier)
  rejection_reason    TEXT
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
  reviewed_at         TIMESTAMPTZ

  INDEX idx_idverif_user_tier_status (user_id, tier, status)
    -- WHY: "does this user have a pending ID_DOCUMENT review" and the
    -- admin review queue ("all PENDING, oldest first") both hit this.
```

```sql
otp_codes
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id        UUID NOT NULL REFERENCES users(id)
  channel        VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL','PHONE'))
  purpose        VARCHAR(20) NOT NULL CHECK (purpose IN ('VERIFY','LOGIN_2FA','PASSWORD_RESET'))
  code_hash      TEXT NOT NULL             -- never store the raw OTP
  expires_at     TIMESTAMPTZ NOT NULL
  attempt_count  SMALLINT NOT NULL DEFAULT 0
  max_attempts   SMALLINT NOT NULL DEFAULT 5
  consumed_at    TIMESTAMPTZ
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_otp_user_purpose_active (user_id, purpose) WHERE consumed_at IS NULL
    -- WHY: "is there already a live OTP for this purpose" is checked on
    -- every resend request to enforce the resend throttle; partial index
    -- skips the (large, over time) consumed rows entirely.
```

```sql
totp_backup_codes
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id     UUID NOT NULL REFERENCES users(id)
  code_hash   TEXT NOT NULL
  used_at     TIMESTAMPTZ
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_backup_codes_user_unused (user_id) WHERE used_at IS NULL
```

```sql
refresh_token_families
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id       UUID NOT NULL REFERENCES users(id)
  device_name   TEXT
  ip_address    INET
  user_agent    TEXT
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  revoked_at    TIMESTAMPTZ
  revoked_reason VARCHAR(30) CHECK (revoked_reason IN ('LOGOUT','REUSE_DETECTED','REVOKE_ALL_DEVICES','ADMIN'))

  INDEX idx_rtf_user_active (user_id) WHERE revoked_at IS NULL
    -- WHY: "revoke all devices" and "list my active sessions" both need
    -- every live family for a user; partial index excludes dead families.
```
The **current** refresh-token hash for a family lives in Redis
(`refresh:{family_id} -> hash, ttl 30d`), not Postgres — validating a
refresh token on every request must be O(1) in-memory, not a Postgres
round trip. Postgres only holds the family's lifecycle (created/revoked)
for audit and for "revoke all devices" to have something durable to act
on. Rotation: each use replaces the Redis value and nothing else; **reuse
of an already-rotated token** (the old hash no longer matches what's in
Redis for that family) revokes the whole family in Postgres and deletes
the Redis key — one compromised token kills every token descended from it.

```sql
login_history
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id         UUID REFERENCES users(id)        -- nullable: failed login with unknown email still logged
  email_attempted CITEXT NOT NULL
  ip_address      INET NOT NULL
  user_agent      TEXT
  success         BOOLEAN NOT NULL
  failure_reason  VARCHAR(30)
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_login_history_user_time (user_id, created_at DESC)
    -- WHY: "show my login history" and the new-device-alert check both
    -- want the most recent rows for a user, newest first.
  INDEX idx_login_history_ip_time (ip_address, created_at)
    -- WHY: per-IP rate limiting / lockout queries count recent failures
    -- by IP across all accounts (credential-stuffing detection).
```

```sql
active_sessions
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id              UUID NOT NULL REFERENCES users(id)
  refresh_family_id    UUID NOT NULL REFERENCES refresh_token_families(id)
  device_name          TEXT
  ip_address           INET
  last_seen_at         TIMESTAMPTZ NOT NULL DEFAULT now()
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
  revoked_at           TIMESTAMPTZ

  INDEX idx_sessions_user_active (user_id) WHERE revoked_at IS NULL
```

### 2. Vibe Profile

```sql
vibe_profiles
  user_id                UUID PRIMARY KEY REFERENCES users(id)
  budget_band            VARCHAR(20) NOT NULL CHECK (budget_band IN ('SHOESTRING','MID','COMFORT','LUXURY'))
  pace                   VARCHAR(20) NOT NULL CHECK (pace IN ('PACKED','BALANCED','SLOW'))
  wake_time              VARCHAR(20) NOT NULL CHECK (wake_time IN ('EARLY_BIRD','AVERAGE','NIGHT_OWL'))
  planning_style         VARCHAR(20) NOT NULL CHECK (planning_style IN ('DETAILED_ITINERARY','ROUGH_PLAN','FULLY_SPONTANEOUS'))
  social_energy          SMALLINT NOT NULL CHECK (social_energy BETWEEN 1 AND 5)
  risk_appetite          SMALLINT NOT NULL CHECK (risk_appetite BETWEEN 1 AND 5)
  food_adventurousness   SMALLINT NOT NULL CHECK (food_adventurousness BETWEEN 1 AND 5)
  expense_split_style    VARCHAR(20) NOT NULL CHECK (expense_split_style IN ('STRICT_SPLIT','WHOEVER_CAN','ONE_PAYS_ALTERNATE'))
  raw_answers            JSONB NOT NULL          -- full 12-15 question quiz responses, for re-scoring if weights change
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
```
WHY individual typed columns instead of one opaque vector/array column:
Stage 3 requires the API to return **explainability** ("both early risers,
mismatch: nightlife"). That requires naming each dimension in application
code. A `VECTOR(8)` or `JSONB` blob would need the same dimension names
duplicated in code anyway, so typed columns buy us DB-level CHECK
validation for free and make `WHERE budget_band = ...` hard-filter queries
a plain indexed column scan instead of a JSON path expression.

```sql
deal_breakers
  user_id             UUID PRIMARY KEY REFERENCES users(id)
  smoking             VARCHAR(10) NOT NULL CHECK (smoking IN ('NO','OK','EITHER'))
  drinking            VARCHAR(10) NOT NULL CHECK (drinking IN ('NO','OK','EITHER'))
  gender_preference   VARCHAR(10) NOT NULL CHECK (gender_preference IN ('ANY','SAME'))
  min_trust_score     SMALLINT NOT NULL DEFAULT 0 CHECK (min_trust_score BETWEEN 0 AND 100)
```
Deal-breakers are intentionally a separate table from `vibe_profiles`: they
are **hard filters** applied in the `WHERE` clause before any soft scoring
happens, and keeping them physically separate makes it obvious in the
repository layer which query is the cheap filter and which is the
expensive weighted-score pass.

### 3. Trips & Membership

```sql
trips
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid()
  host_user_id       UUID NOT NULL REFERENCES users(id)
  title              VARCHAR(140) NOT NULL
  description        TEXT NOT NULL
  destination_name   TEXT NOT NULL
  destination_point  GEOGRAPHY(POINT, 4326) NOT NULL     -- PostGIS
  destination_radius_km NUMERIC(6,2) NOT NULL DEFAULT 0
  start_date         DATE NOT NULL
  end_date           DATE NOT NULL CHECK (end_date >= start_date)
  total_slots        SMALLINT NOT NULL CHECK (total_slots BETWEEN 1 AND 20)
  filled_slots       SMALLINT NOT NULL DEFAULT 0 CHECK (filled_slots <= total_slots)
  budget_band        VARCHAR(20) NOT NULL CHECK (budget_band IN ('SHOESTRING','MID','COMFORT','LUXURY'))
  status             VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('DRAFT','OPEN','FULL','LOCKED','COMPLETED','CANCELLED'))
  version            BIGINT NOT NULL DEFAULT 0        -- @Version, optimistic lock for slot claims
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_trips_destination_gist USING GIST (destination_point)
    -- WHY: PostGIS radius/"near me" browsing (ST_DWithin) needs a spatial
    -- index; a plain B-tree can't accelerate a geography distance query.
  INDEX idx_trips_status_startdate (status, start_date) WHERE status IN ('OPEN','FULL')
    -- WHY: the browse page's default query is "open trips starting soonest"
    -- — partial index excludes locked/completed/cancelled trips, which
    -- dominate the table as it ages.
```
`version` is what makes "two users can't claim the last slot" safe: slot
claims run as `UPDATE trips SET filled_slots = filled_slots + 1, version =
version + 1 WHERE id = ? AND version = ? AND filled_slots < total_slots`
through Spring Data's `@Version`. A naive read-check-then-write (`SELECT
filled_slots`, check `< total_slots` in Java, then `UPDATE`) has a race
window between the read and write; two concurrent requests can both pass
the check and overfill the trip. Optimistic locking turns the second
concurrent writer's commit into an `OptimisticLockException` instead of
silent data corruption — the integration test for this spawns N concurrent
join attempts against a 1-slot trip and asserts exactly one succeeds.

```sql
trip_members
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id     UUID NOT NULL REFERENCES trips(id)
  user_id     UUID NOT NULL REFERENCES users(id)
  status      VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                CHECK (status IN ('REQUESTED','ACCEPTED','REJECTED','LOCKED','CANCELLED','COMPLETED'))
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
  decided_at   TIMESTAMPTZ
  locked_at    TIMESTAMPTZ

  UNIQUE (trip_id, user_id)
    -- WHY: prevents duplicate join requests from the same user to the
    -- same trip; also the natural key the chat module uses to find "the
    -- conversation between this host and this requester for this trip".
  INDEX idx_trip_members_user_status (user_id, status)
    -- WHY: "my trips" dashboard filters a single user's memberships by
    -- status across all trips.
```

```sql
match_score_cache
  trip_id         UUID NOT NULL REFERENCES trips(id)
  user_id         UUID NOT NULL REFERENCES users(id)
  score           SMALLINT NOT NULL CHECK (score BETWEEN 0 AND 100)
  explanation     JSONB NOT NULL         -- {matches: [...], mismatches: [...]}
  computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()

  PRIMARY KEY (trip_id, user_id)
```
A cache, not a source of truth — invalidated (row deleted) whenever either
party's `vibe_profiles` row changes. Recomputing a weighted score across a
browse page of 50 trips on every request is wasted CPU when vibe profiles
change rarely; this is a plain read-through cache, not an event-sourced
table, so it carries no `version` or history.

### 4. Guided Masked Chat

```sql
conversations
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id             UUID NOT NULL REFERENCES trips(id)
  host_user_id        UUID NOT NULL REFERENCES users(id)
  requester_user_id   UUID NOT NULL REFERENCES users(id)
  status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED'))
  contact_unlocked_at TIMESTAMPTZ          -- set at trip lock; null = still masked
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()

  UNIQUE (trip_id, requester_user_id)
  INDEX idx_conversations_host (host_user_id)
    -- WHY: a trip host's inbox lists every conversation for trips they host.
```

```sql
chat_messages
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
  conversation_id  UUID NOT NULL REFERENCES conversations(id)
  sender_id        UUID REFERENCES users(id)     -- null for SYSTEM/AI icebreaker messages
  body             TEXT NOT NULL                 -- sanitized (java-html-sanitizer) + PII-redacted BEFORE insert
  flagged          BOOLEAN NOT NULL DEFAULT FALSE
  flag_reason      VARCHAR(30) CHECK (flag_reason IN ('OFF_PLATFORM_PUSH','MONEY_REQUEST','HARASSMENT'))
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_chat_messages_conversation_time (conversation_id, created_at)
    -- WHY: paginated message history for one conversation, chronological —
    -- the single hottest query in the whole chat module.
```
Progressive disclosure is enforced by never storing real contact details
in a message in the first place — the PII-redaction regex runs server-side
on every inbound message before persistence, so there is no "unmask"
operation to reverse-engineer later; `contact_unlocked_at` on
`conversations` only gates a *separate* endpoint that reveals the other
party's real email/phone once set.

```sql
moderation_flags
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
  chat_message_id  UUID NOT NULL REFERENCES chat_messages(id)
  flag_type        VARCHAR(30) NOT NULL CHECK (flag_type IN ('OFF_PLATFORM_PUSH','MONEY_REQUEST','HARASSMENT'))
  confidence       NUMERIC(4,3) NOT NULL
  auto_action      VARCHAR(30) CHECK (auto_action IN ('NONE','TRUST_SCORE_PENALTY','AUTO_SUSPEND'))
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
```

### 5. Escrow & Ledger (money integrity)

```sql
wallets
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id         UUID NOT NULL UNIQUE REFERENCES users(id)
  balance_cache   NUMERIC(12,2) NOT NULL DEFAULT 0   -- denormalized, MUST equal SUM(ledger_entries) for this account
  currency        VARCHAR(3) NOT NULL DEFAULT 'INR'
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
```
`balance_cache` is a read optimization only — it is recomputed from
`ledger_entries` by a scheduled reconciliation job and any mismatch is
logged as a critical alert. The ledger, never this column, is the legal
source of truth for how much money exists.

```sql
ledger_entries        -- APPEND-ONLY, no UPDATE/DELETE grants
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
  transaction_id    UUID NOT NULL          -- groups the two (or more) legs of one double-entry transaction
  account_id        UUID NOT NULL          -- wallets.id, or a platform holding account for escrow-in-flight
  entry_type        VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT'))
  amount            NUMERIC(12,2) NOT NULL CHECK (amount > 0)
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR'
  reference_type    VARCHAR(30) NOT NULL CHECK (reference_type IN
                       ('ESCROW_DEPOSIT','ESCROW_RELEASE','ESCROW_FORFEIT','ESCROW_REFUND','EXPENSE_SETTLEMENT'))
  reference_id      UUID NOT NULL          -- escrows.id or expense_shares.id
  idempotency_key   VARCHAR(100) NOT NULL UNIQUE
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_ledger_account_time (account_id, created_at)
    -- WHY: "this wallet's statement" and balance reconciliation both sum
    -- entries for one account in time order.
  INDEX idx_ledger_transaction (transaction_id)
    -- WHY: verifying a transaction's two legs balance (SUM(debits) =
    -- SUM(credits)) groups by this.
```
`idempotency_key` is the single most important column in the schema for
money safety: every money-moving endpoint requires a client-supplied (or
server-generated-and-returned) idempotency key, and the `UNIQUE`
constraint means a retried request (network blip, double-click, replayed
request) throws a constraint violation instead of moving money twice — the
service layer catches that specific violation and returns the original
result instead of erroring.

```sql
escrows
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id         UUID NOT NULL REFERENCES trips(id)
  user_id         UUID NOT NULL REFERENCES users(id)
  amount          NUMERIC(12,2) NOT NULL CHECK (amount > 0)
  currency        VARCHAR(3) NOT NULL DEFAULT 'INR'
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','LOCKED','RELEASED','FORFEITED','REFUNDED'))
  version         BIGINT NOT NULL DEFAULT 0     -- @Version, optimistic lock
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()

  UNIQUE (trip_id, user_id)
  INDEX idx_escrows_status (status) WHERE status IN ('PENDING','LOCKED')
    -- WHY: the scheduler that checks "trip locked but escrow still
    -- PENDING for too long -> alert" only ever looks at non-terminal rows.
```

```sql
escrow_state_transitions    -- APPEND-ONLY
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
  escrow_id   UUID NOT NULL REFERENCES escrows(id)
  from_status VARCHAR(20) NOT NULL
  to_status   VARCHAR(20) NOT NULL
  reason      VARCHAR(50) NOT NULL
  actor_id    UUID REFERENCES users(id)    -- null if system/scheduler-driven
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_escrow_transitions_escrow (escrow_id, created_at)
```

```sql
expenses
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id         UUID NOT NULL REFERENCES trips(id)
  paid_by_user_id UUID NOT NULL REFERENCES users(id)
  amount          NUMERIC(12,2) NOT NULL CHECK (amount > 0)
  description     TEXT NOT NULL
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()

expense_shares
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  expense_id      UUID NOT NULL REFERENCES expenses(id)
  user_id         UUID NOT NULL REFERENCES users(id)
  share_amount    NUMERIC(12,2) NOT NULL CHECK (share_amount >= 0)
  settled_at      TIMESTAMPTZ

  UNIQUE (expense_id, user_id)
  INDEX idx_expense_shares_user_unsettled (user_id) WHERE settled_at IS NULL
    -- WHY: "what do I owe across all my trips" dashboard query.
```
Settling a share inserts a `ledger_entries` row with
`reference_type = 'EXPENSE_SETTLEMENT'` against the payer's and payee's
wallets — expenses reuse the same ledger rather than having their own
balance column, so there is exactly one place money ever moves.

### 6. Live Trip Mode & Safety

```sql
itinerary_shares
  id                              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id                         UUID NOT NULL REFERENCES trips(id)
  user_id                         UUID NOT NULL REFERENCES users(id)
  emergency_contact_name          TEXT NOT NULL
  emergency_contact_phone_ciphertext BYTEA NOT NULL
  shared_at                       TIMESTAMPTZ NOT NULL DEFAULT now()

  UNIQUE (trip_id, user_id)
```

```sql
checkins
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id        UUID NOT NULL REFERENCES trips(id)
  user_id        UUID NOT NULL REFERENCES users(id)
  scheduled_at   TIMESTAMPTZ NOT NULL
  status         VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','MISSED'))
  responded_at   TIMESTAMPTZ
  location_point GEOGRAPHY(POINT, 4326)        -- coarse point for map display, see note below
  location_ciphertext BYTEA                    -- exact coordinates, AES-GCM, PII-sensitive
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_checkins_pending_due (scheduled_at) WHERE status = 'PENDING'
    -- WHY: the scheduler that detects missed check-ins scans "PENDING
    -- rows whose scheduled_at has passed", every few minutes.
```
Precise location is dual-stored deliberately: `location_point` is rounded
to ~1km (geohash-truncated) before being written as a plain PostGIS point
so "show buddy's last area on a map" and SOS proximity queries can use a
GIST index; the true precise coordinates only exist encrypted in
`location_ciphertext` and are decrypted only for the SOS escalation path
(emergency contact / platform safety team), never for routine map display.
This is the same reasoning as stripping EXIF GPS from uploaded photos —
precise location is the single most sensitive field in a safety app and
defaults to encrypted-at-rest everywhere it isn't actively needed in the
clear.

```sql
sos_events
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id                  UUID NOT NULL REFERENCES trips(id)
  user_id                  UUID NOT NULL REFERENCES users(id)
  triggered_at             TIMESTAMPTZ NOT NULL DEFAULT now()
  last_known_location_ciphertext BYTEA
  status                   VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ESCALATED','RESOLVED'))
  resolved_at              TIMESTAMPTZ
  resolution_notes         TEXT

  INDEX idx_sos_open (status) WHERE status <> 'RESOLVED'
    -- WHY: the on-call/admin safety dashboard polls exactly this set.
```

### 7. Reviews & Trust Score

```sql
reviews
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id        UUID NOT NULL REFERENCES trips(id)
  reviewer_id    UUID NOT NULL REFERENCES users(id)
  reviewee_id    UUID NOT NULL REFERENCES users(id)
  rating         SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5)
  comment        TEXT
  submitted_at   TIMESTAMPTZ NOT NULL DEFAULT now()
  visible_at     TIMESTAMPTZ          -- set (= now of the second submission) once BOTH sides have submitted

  UNIQUE (trip_id, reviewer_id, reviewee_id)
  INDEX idx_reviews_reviewee_visible (reviewee_id) WHERE visible_at IS NOT NULL
    -- WHY: trust score recalculation and public profile display both read
    -- only visible reviews for a user; invisible (pending double-blind)
    -- rows must never leak through either path.
```
Double-blind is enforced in the service layer at write time, not by
hiding rows from a query: inserting the first review of a pair leaves
`visible_at` null; inserting the second one sets `visible_at = now()` on
**both** rows in the same transaction. A read path that forgot the
`WHERE visible_at IS NOT NULL` filter would leak an unsubmitted-partner's
review, so this is also covered by an authorization-style test, not just
trusted to application code discipline.

```sql
trust_score_events
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id     UUID NOT NULL REFERENCES users(id)
  event_type  VARCHAR(30) NOT NULL CHECK (event_type IN
                ('VERIFICATION_TIER_CHANGE','REVIEW_VISIBLE','TRIP_COMPLETED','GHOST','RESPONSE_RATE_SAMPLE'))
  payload     JSONB NOT NULL
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  processed_at TIMESTAMPTZ

  INDEX idx_trust_events_unprocessed (user_id) WHERE processed_at IS NULL
    -- WHY: the trust score scheduler's whole query is "unprocessed events,
    -- grouped by user" — this partial index is that query.
```

```sql
trust_score_snapshots
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id        UUID NOT NULL REFERENCES users(id)
  score          NUMERIC(5,2) NOT NULL
  components     JSONB NOT NULL        -- {verification: .., responseRate: .., ghostRate: .., completion: .., peerRating: ..}
  computed_at    TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_trust_snapshots_user_time (user_id, computed_at DESC)
```
Snapshots exist so the "why is my score X" explainability and any future
score-dispute investigation can see the component breakdown at a point in
time, not just the current denormalized `users.trust_score`.

### 8. Platform (outbox, audit, compliance)

```sql
outbox_events
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  aggregate_type VARCHAR(30) NOT NULL        -- e.g. 'TRIP', 'ESCROW', 'REVIEW'
  aggregate_id   UUID NOT NULL
  event_type     VARCHAR(50) NOT NULL
  payload        JSONB NOT NULL
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
  processed_at   TIMESTAMPTZ
  attempts       SMALLINT NOT NULL DEFAULT 0

  INDEX idx_outbox_unprocessed (created_at) WHERE processed_at IS NULL
    -- WHY: the relay/scheduler's only query, in FIFO order.
```
The outbox pattern is why trust score and other cross-module reactions
never run inline inside a request thread: a service that changes trip/
escrow/review state writes its row **and** an outbox row in the same DB
transaction (so the event can never be "lost" relative to the state
change), and a separate scheduled poller reads unprocessed outbox rows and
dispatches them (e.g. into `trust_score_events`). This decouples "a review
got submitted" from "trust score got recalculated" so a slow recalculation
never adds latency to the review-submission request, and a crash between
the two can never leave one done and the other silently skipped.

```sql
audit_log            -- APPEND-ONLY, hash-chained
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  sequence_no    BIGINT NOT NULL UNIQUE       -- gapless, assigned by a DB sequence
  prev_hash      VARCHAR(64) NOT NULL         -- SHA-256 of the previous row's canonical representation
  row_hash       VARCHAR(64) NOT NULL         -- SHA-256(prev_hash || this row's own fields)
  actor_user_id  UUID REFERENCES users(id)
  event_type     VARCHAR(50) NOT NULL         -- AUTH_LOGIN, ESCROW_FORFEIT, ADMIN_SUSPEND, SOS_TRIGGERED, ...
  entity_type    VARCHAR(30)
  entity_id      UUID
  metadata       JSONB NOT NULL DEFAULT '{}'
  ip_address     INET
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_audit_entity (entity_type, entity_id)
  INDEX idx_audit_sequence (sequence_no)
```
Every auth event, safety event, money movement, and admin action writes
one row here, in the same transaction as the business change. The hash
chain means an attacker (including a rogue DB admin) who edits or deletes
a historical row breaks the `row_hash` of every row after it — a scheduled
integrity check recomputes the chain and alerts on the first mismatch.
This buys *tamper-evidence*, not tamper-prevention (that's what the
missing `UPDATE/DELETE` grants are for) — the two mechanisms are
deliberately layered.

```sql
consent_records
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id       UUID NOT NULL REFERENCES users(id)
  consent_type  VARCHAR(30) NOT NULL CHECK (consent_type IN ('TOS','PRIVACY_POLICY','LOCATION_SHARING','MARKETING'))
  policy_version VARCHAR(20) NOT NULL
  granted_at    TIMESTAMPTZ
  revoked_at    TIMESTAMPTZ
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_consent_user_type (user_id, consent_type)
```

```sql
data_requests
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id        UUID NOT NULL REFERENCES users(id)
  type           VARCHAR(20) NOT NULL CHECK (type IN ('EXPORT','DELETION'))
  status         VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED'))
  export_file_key TEXT
  requested_at   TIMESTAMPTZ NOT NULL DEFAULT now()
  completed_at   TIMESTAMPTZ
```

```sql
blocks_reports
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
  reporter_id   UUID NOT NULL REFERENCES users(id)
  reported_id   UUID NOT NULL REFERENCES users(id)
  type          VARCHAR(10) NOT NULL CHECK (type IN ('BLOCK','REPORT'))
  reason        VARCHAR(30)
  details       TEXT
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()

  UNIQUE (reporter_id, reported_id, type)
  INDEX idx_blocks_reports_reported (reported_id, type)
    -- WHY: auto-suspend threshold logic counts REPORT rows against a
    -- reported_id within a rolling window — this is that query's index.
```

---

## Trust Score Engine spec

**Inputs** (each a row in `trust_score_events`, written by the owning
module's outbox consumer, never computed inline):
1. `VERIFICATION_TIER_CHANGE` — base points per tier: EMAIL=10, PHONE=20,
   ID_DOCUMENT=35, SELFIE_MATCH=50 (cumulative tier, not additive — a user
   at SELFIE_MATCH gets 50, not 10+20+35+50).
2. `REVIEW_VISIBLE` — a star rating (1-5) from a completed trip partner.
3. `TRIP_COMPLETED` — did the user actually show up and finish the trip.
4. `GHOST` — user accepted a trip_member slot then cancelled after lock,
   or no-showed (detected via missed check-ins + no completion).
5. `RESPONSE_RATE_SAMPLE` — periodic sample of (messages replied to /
   messages received) in chat.

**Formula**, recomputed by a scheduler that drains unprocessed
`trust_score_events` per user (never inline in a request):

```
score = clamp(0, 100,
    0.30 * verification_component
  + 0.30 * bayesian_peer_rating_component
  + 0.20 * completion_rate_component     -- trips completed / trips committed to
  + 0.15 * ghost_penalty_component       -- 100 - (ghosts / commitments * 100)
  + 0.05 * response_rate_component
)
```

- **Bayesian peer rating component**: plain averaging lets one 5-star
  review outrank a user with 40 reviews averaging 4.2. We use a Bayesian
  weighted average against a global prior:
  `bayesian_avg = (prior_weight * prior_mean + Σratings) / (prior_weight + n)`
  with `prior_weight = 10` and `prior_mean = 3.5` (platform-wide average,
  recomputed monthly). A brand-new user with one 5-star review gets
  `(10*3.5 + 5) / 11 ≈ 3.64`, not 5 — it takes a consistent track record to
  move far from the prior. `n` is the count of **visible** reviews only.
- **Time decay**: every input event's weight decays with
  `exp(-λ * age_in_days)`, `λ` tuned so a signal from 12 months ago counts
  for roughly 15% of a signal from today. This applies to peer ratings and
  response-rate samples (behavior can improve), but deliberately **not**
  to `GHOST` events for the first 2 occurrences — a ghosting history
  shouldn't fully "fade away" quickly early on; the decay floor for ghost
  penalties is capped at 50% rather than approaching 0%.
- Every recomputation writes one `trust_score_snapshots` row (full
  component breakdown) and updates the `users.trust_score` /
  `trust_score_updated_at` cache columns in the same transaction.

## Escrow State Machine spec

States: `PENDING, LOCKED, RELEASED, FORFEITED, REFUNDED`.
`RELEASED`, `FORFEITED`, and `REFUNDED` are **terminal** — no row ever
leaves them. This is a graph, not a straight line:

```
            create escrow (user agrees to deposit)
                      |
                      v
                  PENDING ───────────────► REFUNDED
                      |     trip cancelled/        (host cancels trip,
                      |     under-filled before     or doesn't reach
                      |     lock deadline            min slot count)
                      |
                      | both parties' escrows PENDING
                      | AND trip lock confirmation
                      | (video-verification step) done
                      v
                   LOCKED ───────────────► FORFEITED
                      |     party cancels/            (penalty: funds
                      |     no-shows after lock        move to platform
                      |                                pool + counterparty
                      |                                compensation split)
                      | trip end_date passed,
                      | no SOS/dispute open
                      v
                  RELEASED
            (funds credited back to
             user's wallet balance)
```

Every transition is a single DB transaction that:
1. Optimistically locks the `escrows` row (`WHERE version = ?`).
2. Inserts the matching double-entry `ledger_entries` rows (e.g. LOCKED→
   RELEASED debits the platform holding account and credits the user's
   wallet) with a request-scoped idempotency key.
3. Inserts one `escrow_state_transitions` row.
4. Writes an `outbox_events` row so trust score / audit can react.

No transition is ever expressed as `UPDATE escrows SET status = ...` done
in isolation — status, ledger effect, and transition history are written
together or not at all, which is what makes "replay this request" safe
(step 2's idempotency key makes the whole transaction a no-op on retry)
and what makes the state auditable after the fact independent of the
`escrows` row's current value.
