# TripMates — DESIGN.md

This document is the source of truth for the data model, module boundaries,
and the three core state machines (Trust Score, Settlement, Public Meet-up).
It must stay in sync with the Flyway migrations under
`src/main/resources/db/migration`. If code and this doc disagree, the code
is wrong until this doc is updated and the reason is recorded in a commit
message.

Conventions used everywhere below unless stated otherwise:
- Primary keys: `UUID DEFAULT gen_random_uuid()`. Reason: trip/user/
  settlement IDs are exposed in URLs and WebSocket topics; sequential
  bigints make IDOR enumeration trivial ("try id+1"). UUIDs don't fix
  authorization by themselves (we still check ownership on every request)
  but they remove free enumeration as an attack surface.
- Timestamps: `TIMESTAMPTZ`, always UTC.
- Money: `NUMERIC(12,2)` with a fixed currency column (`INR` only for now,
  but we always carry a `currency` column rather than assume one — adding
  multi-currency later must never become a silent precision bug). Money
  columns in this system record **obligations between users**, never
  platform-held funds — see "The platform never holds money" below.
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

## The platform never holds money

TripMates never takes custody of a single rupee. Reasoning:
- Holding user funds in India (even "just" a refundable commitment amount)
  makes TripMates a Prepaid Payment Instrument issuer under RBI rules,
  which requires a PPI licence and nodal/escrow bank accounts — a
  regulatory burden with zero product payoff for a pre-revenue platform.
- No stranger is going to wire a deposit to a new matching app before a
  trip anyway — asking for one adds friction exactly where trust is
  weakest, not where it's needed.
- Splitwise is the reference point here: it has settled trillions in
  informal group spending while holding none of it. Not holding money
  isn't a limitation of the product, it's the smartest decision in it.

So the "commitment" in Stage 5 is **reputation, not money** (see Trust
Score Engine), and the expense-split feature (Stage "Also build") is a
**ledger of IOUs plus a UPI deep link to settle outside the platform**, not
an escrow. See the Settlement & Ledger section below.

## No government ID verification

TripMates never collects, uploads, or stores a government ID — no
passport, no driver's licence, no Aadhaar — anywhere in this system.
Reasoning:
- **Regulatory**: the 2018 Supreme Court ruling (*Justice K.S. Puttaswamy
  v. Union of India*) struck down Section 57 of the Aadhaar Act, which had
  let private companies demand Aadhaar for authentication. The Aadhaar
  Authentication for Good Governance (Social Welfare, Innovation,
  Knowledge) Amendment Rules, 2025 reopened a narrow door for travel/
  hospitality companies to use Aadhaar authentication — but only after a
  ministry application and UIDAI approval. That's not available to a
  pre-revenue student project, and building a document pipeline around a
  regulatory door we can't walk through is wasted engineering.
- **Behavioural**: almost nobody uploads a government ID to an unknown
  matching app, and a verification step people refuse to complete isn't a
  safety feature — it's an empty funnel that filters out everyone except
  the users who'd pass anyway.
- **Privacy**: data that is never collected can never be breached. Not
  storing a government ID at all is a strictly stronger privacy property
  than storing it encrypted, which was already this project's standard
  for sensitive fields.
- **Threat fit**: the actual risk in this product is not "is this
  person's legal name correct" — it's "are the photos actually of this
  person, and do they have a track record I can see." A document scan
  answers the first question; it says nothing about the second, and the
  second is what matching with a stranger actually depends on. Selfie
  liveness matching targets the real question directly.

**Consequence, stated plainly, not hidden**: this is weaker identity
assurance than a government-ID check would provide. The Public Meet-up
Protocol (§5) exists partly to carry more of the physical-safety burden
that a document check would otherwise share — a real, public, daylight,
geofenced-and-selfie-confirmed meeting before trip lock is the
compensating control for *not* having a document on file, not an
unrelated feature. This trade-off is deliberate and documented here so it
can be revisited (e.g. if UIDAI approval ever became realistic for this
product), not an oversight discovered later.

**Verification is rewarded, never demanded.** Every tier is optional and
the app stays fully usable unverified — there is no gate that blocks
basic use. What changes with tier is *visibility*: `deal_breakers`
lets any user set `min_trust_score`, and verification tier is the
single biggest input to that score (see Trust Score Engine). An
unverified user isn't blocked, they're simply invisible to the slice of
the user base that filters strictly — the demand for verification comes
from other users wanting it, the platform never forces it on anyone.

## Module boundaries

Each module is a Java package under `com.tripmate.<module>`, with its own
`controller / service / repository / dto / entity` sub-packages. Modules
talk to each other only through service interfaces, never by reaching into
another module's repository or entity directly — this is what makes the
authorization tests and the trust-score outbox possible without circular
dependencies.

| Module | Owns tables | Depends on |
|---|---|---|
| `identity` | users, user_encryption_keys, identity_verifications, vouches, otp_codes, totp_backup_codes, refresh_token_families, login_history, active_sessions | — (foundation) |
| `vibe` | vibe_profiles, deal_breakers | identity |
| `trips` | trips, trip_members, match_score_cache | identity, vibe |
| `chat` | conversations, chat_messages, moderation_flags | identity, trips |
| `meetup` | meetups, meetup_checkins, meetup_feedback, verified_venues | identity, trips |
| `settlement` | ledger_entries, settlements, settlement_state_transitions, expenses, expense_shares | identity, trips |
| `safety` | itinerary_shares, checkins, sos_events | identity, trips |
| `trust` | reviews, trust_score_events, trust_score_snapshots | identity, trips, settlement, safety |
| `platform` | outbox_events, audit_log, consent_records, data_requests, blocks_reports | all (infra module, no business logic) |

`trust` is deliberately downstream of everything else: it only ever reacts
to events other modules publish to the outbox. No module calls into
`trust` synchronously to "update my score" — see the Trust Score Engine
section. `meetup` is deliberately **not** a dependency of `trust`:
`meetup_feedback` (the private comfort rating) must never be reachable
from any code path that feeds the public trust score or profile display —
keeping it in a module `trust` doesn't depend on makes that a compile-time
fact, not just a discipline.

## Append-only enforcement

`ledger_entries`, `settlement_state_transitions`, `audit_log`, and
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
  email                   CITEXT NOT NULL
  password_hash           TEXT NOT NULL                -- Argon2id
  full_name               TEXT NOT NULL
  date_of_birth           DATE NOT NULL
                            CHECK (date_of_birth <= (now() - INTERVAL '18 years'))
  gender                  VARCHAR(20) NOT NULL CHECK (gender IN ('MALE','FEMALE','NON_BINARY','PREFER_NOT_TO_SAY'))
  phone_ciphertext        BYTEA                         -- AES-GCM envelope-encrypted
  phone_blind_index       VARCHAR(64)                   -- HMAC-SHA256(phone), indexed, for uniqueness/lookup without decrypting
  upi_vpa                 VARCHAR(100)                  -- optional, self-declared, user@bank — only used to build a settlement deep link
  profile_photo_key       TEXT                          -- object storage key, never a public URL
  email_verified_at       TIMESTAMPTZ
  phone_verified_at       TIMESTAMPTZ
  id_verification_tier    VARCHAR(20) NOT NULL DEFAULT 'NONE'
                            CHECK (id_verification_tier IN ('NONE','EMAIL','PHONE','SELFIE_MATCH'))
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

  UNIQUE INDEX idx_users_email_active ON users(email) WHERE deleted_at IS NULL
    -- WHY: a plain column-level UNIQUE would permanently block a
    -- soft-deleted user's email from ever being used to re-register
    -- (the old row still exists, deleted_at just marks it dead). Scoping
    -- the uniqueness to "not deleted" rows lets the email be reclaimed —
    -- a hard requirement once deletion is soft-delete + crypto-shred, not
    -- a physical DELETE.
  INDEX idx_users_phone_blind_index (phone_blind_index)
    -- WHY: lets us enforce "one account per phone number" and look up by
    -- phone (e.g. OTP login) with an equality match on an HMAC, without
    -- ever decrypting phone_ciphertext for a read-path lookup.
  INDEX idx_users_account_status (account_status) WHERE account_status <> 'ACTIVE'
    -- WHY: admin moderation queries ("show me suspended users") filter on
    -- this constantly; partial index keeps it tiny since >99% of rows are ACTIVE.
```
The 18+ `CHECK` is a hard DB-level floor, not just a signup-form
validation: a platform whose entire purpose is introducing strangers to
each other must never admit a minor, and that guarantee can't live only
in a form that a direct API call bypasses. The service layer duplicates
the check (for a clear, immediate error message); the constraint is the
backstop if it doesn't. See `THREAT_MODEL.md`.

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
  tier                VARCHAR(20) NOT NULL CHECK (tier IN ('PHONE','SELFIE_MATCH'))
  status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED'))
  similarity_score    NUMERIC(4,3)                 -- SELFIE_MATCH only: liveness/face-match confidence, null for PHONE
  selfie_image_key    TEXT                         -- temporary; purged by a scheduled job within the retention window, see below
  purge_due_at        TIMESTAMPTZ                  -- SELFIE_MATCH only: created_at + retention window (default 7 days)
  purged_at           TIMESTAMPTZ
  reviewer_id         UUID REFERENCES users(id)     -- admin, only for flagged/ambiguous SELFIE_MATCH cases — most are auto-decided
  rejection_reason    TEXT
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
  reviewed_at         TIMESTAMPTZ

  INDEX idx_idverif_user_tier_status (user_id, tier, status)
    -- WHY: "does this user have a pending SELFIE_MATCH review" and the
    -- admin review queue ("all flagged PENDING, oldest first") both hit this.
  INDEX idx_idverif_purge_due (purge_due_at) WHERE selfie_image_key IS NOT NULL
    -- WHY: the scheduled purge job's only query — rows still holding an
    -- image key whose retention window has passed, regardless of status.
```
There is no document pipeline here at all — no document type, no scanned
number, no retention beyond a verification decision. `tier = 'PHONE'` rows
never have an image; `tier = 'SELFIE_MATCH'` rows hold `selfie_image_key`
only long enough to compare it against `users.profile_photo_key` and let a
flagged case reach a human reviewer, then a scheduled job purges it —
`purged_at` is set and `selfie_image_key` is nulled once `purge_due_at`
passes, independent of whether the verification was approved or rejected.
The invariant this buys: **no selfie image survives past its retention
window in a terminal (`APPROVED`/`REJECTED`) state** — enforced by the
purge job and asserted directly by a test that creates a verification,
fast-forwards past `purge_due_at`, runs the job, and checks
`selfie_image_key IS NULL`.

```sql
vouches
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid()
  voucher_user_id    UUID NOT NULL REFERENCES users(id)
  vouched_user_id    UUID NOT NULL REFERENCES users(id) CHECK (vouched_user_id <> voucher_user_id)
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
  revoked_at         TIMESTAMPTZ

  UNIQUE (voucher_user_id, vouched_user_id)
  INDEX idx_vouches_vouched_active (vouched_user_id) WHERE revoked_at IS NULL
    -- WHY: the trust score scheduler reads "who actively vouches for me"
    -- to compute this user's vouch bonus.
  INDEX idx_vouches_voucher_active (voucher_user_id) WHERE revoked_at IS NULL
    -- WHY: two uses — enforcing the active-vouch cap ("how many people is
    -- X currently vouching for, right now") when a new vouch is created,
    -- and the penalty-propagation job ("this user was just suspended —
    -- who vouches for them") walking the *other* direction on the
    -- vouched side's own idx_vouches_vouched_active index.
```
Vouching requires the voucher to hold `SELFIE_MATCH` tier themselves
(service-layer check — an unverified account has no standing to vouch for
anyone) and is capped at a small number of concurrently active vouches
per voucher (e.g. 5), enforced against `idx_vouches_voucher_active` before
insert. The cap exists so one account can't become a mass-vouching hub
that launders trust for a ring of fake accounts; "skin in the game" only
works as a signal if vouching is scarce and costly to the voucher. See
Trust Score Engine → Vouching for the bonus/penalty mechanics.

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
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id               UUID NOT NULL REFERENCES trips(id)
  user_id               UUID NOT NULL REFERENCES users(id)
  status                VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                          CHECK (status IN ('REQUESTED','ACCEPTED','REJECTED','LOCKED','CANCELLED','COMPLETED'))
  video_verified_at     TIMESTAMPTZ          -- mutual in-app video call confirmation (mock), gates meetup proposal
  cancellation_type     VARCHAR(20) CHECK (cancellation_type IN ('CONVENIENCE','SAFETY'))
  cancellation_reason   TEXT
  requested_at          TIMESTAMPTZ NOT NULL DEFAULT now()
  decided_at            TIMESTAMPTZ
  locked_at             TIMESTAMPTZ

  UNIQUE (trip_id, user_id)
    -- WHY: prevents duplicate join requests from the same user to the
    -- same trip; also the natural key the chat/meetup modules use to find
    -- "the conversation/meetup between this host and this requester for
    -- this trip".
  INDEX idx_trip_members_user_status (user_id, status)
    -- WHY: "my trips" dashboard filters a single user's memberships by
    -- status across all trips.
```
The trust ladder this schema encodes: `REQUESTED` → `ACCEPTED` (masked
chat opens) → `video_verified_at` set (in-app video call, mock) → a
`meetups` row reaches `COMPLETED` with no below-threshold
`meetup_feedback` → `status = LOCKED`. No escrow deposit anywhere in that
chain — see "The platform never holds money".

`cancellation_type` is the safety carve-out: a `CANCELLED` row with
`cancellation_type = 'SAFETY'` **never** emits a `GHOST` trust-score event
and costs nothing, by construction — the service method that records a
safety cancellation is physically incapable of calling the same code path
that writes a `GHOST` `trust_score_events` row; that path only exists on
the `CONVENIENCE` branch. See Trust Score Engine → Safety carve-out.

```sql
match_score_cache
  trip_id         UUID NOT NULL REFERENCES trips(id)
  user_id         UUID NOT NULL REFERENCES users(id)
  score           SMALLINT NOT NULL CHECK (score BETWEEN 0 AND 100)
  explanation     JSONB NOT NULL         -- {matches: [...], mismatches: [...]}
  computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()

  PRIMARY KEY (trip_id, user_id)
  INDEX idx_match_score_cache_user (user_id)
    -- WHY: invalidating this cache happens by user_id alone ("this
    -- person's vibe profile changed, drop every cached score involving
    -- them") on every vibe_profiles edit. The PK is (trip_id, user_id), so
    -- without this index a by-user invalidation is a full table scan;
    -- with it, it's an index lookup that scales with that user's trip
    -- count, not the whole cache's size.
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

### 5. Public Meet-up Protocol

The trust ladder before a trip can lock is: masked chat → in-app video
verification (`trip_members.video_verified_at`) → **a public meet-up** →
trip lock → live trip mode. This rung was previously missing — trust
score and masked chat alone let two people go straight from text messages
to a multi-day trip together, which is the single biggest physical-safety
gap in the whole design. A short, low-stakes, public, daylight meeting is
cheap insurance against it.

```sql
verified_venues
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
  name        TEXT NOT NULL
  point       GEOGRAPHY(POINT, 4326) NOT NULL
  address     TEXT NOT NULL
  category    VARCHAR(30) NOT NULL CHECK (category IN ('CAFE','MALL','RESTAURANT','PUBLIC_LANDMARK','TRANSIT_HUB'))
  active      BOOLEAN NOT NULL DEFAULT TRUE
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_verified_venues_gist USING GIST (point)
    -- WHY: proposing a meetup needs "venues near this trip's destination",
    -- a PostGIS radius query exactly like trip browsing.
```
A meet-up can only be proposed at a venue from this allowlist — curated,
genuinely public, well-lit, CCTV'd-in-practice places (malls, cafés,
transit hubs), seeded by the platform/admin, never a user-supplied
address. This closes "venue spoofing": a bad actor proposing a private or
isolated location dressed up as a venue name.

```sql
meetups
  id                            UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id                       UUID NOT NULL REFERENCES trips(id)
  host_user_id                  UUID NOT NULL REFERENCES users(id)
  requester_user_id             UUID NOT NULL REFERENCES users(id)
  venue_id                      UUID NOT NULL REFERENCES verified_venues(id)
  venue_name                    TEXT NOT NULL      -- denormalized from verified_venues at proposal time
  venue_point                   GEOGRAPHY(POINT, 4326) NOT NULL  -- denormalized, so geofence checks need no join
  scheduled_at                  TIMESTAMPTZ NOT NULL
  status                        VARCHAR(20) NOT NULL DEFAULT 'PROPOSED'
                                   CHECK (status IN ('PROPOSED','CONFIRMED','COMPLETED','CANCELLED','NO_SHOW'))
  host_plus_one                 BOOLEAN NOT NULL DEFAULT FALSE
  requester_plus_one            BOOLEAN NOT NULL DEFAULT FALSE
  emergency_contact_notified_at TIMESTAMPTZ
  created_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now()

  UNIQUE (trip_id, requester_user_id)
  INDEX idx_meetups_confirmed_schedule (scheduled_at) WHERE status = 'CONFIRMED'
    -- WHY: the 30/60-minute "did you arrive" ping scheduler scans
    -- confirmed, not-yet-resolved meetups ordered by scheduled time —
    -- the partial index excludes proposed/completed/cancelled rows.
```
Server-side validation beyond the schema (service layer, not a portable
SQL `CHECK`): `scheduled_at` must fall within local daylight hours for the
venue's location, and `venue_id` must reference an `active` row in
`verified_venues` — a user cannot free-text a venue. Denormalizing
`venue_name`/`venue_point` onto the row avoids a join on the hot path (the
geofence check on arrival) while `venue_id` keeps the allowlist constraint
enforceable at the foreign-key level, not just "the API happened to check
it once."

```sql
meetup_checkins
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
  meetup_id     UUID NOT NULL REFERENCES meetups(id)
  user_id       UUID NOT NULL REFERENCES users(id)
  arrived_at    TIMESTAMPTZ
  arrival_point GEOGRAPHY(POINT, 4326)        -- must satisfy ST_DWithin(venue_point, arrival_point, 100) to count as arrived
  selfie_key    TEXT                          -- same upload pipeline as verification selfies: magic-byte check, EXIF-stripped, signed URL only
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()

  UNIQUE (meetup_id, user_id)
```
A "faked geofence check-in" (spoofed GPS claiming arrival while not
actually there) is a real threat for a safety feature — the 100m
`ST_DWithin` check is a soft signal, not a hard guarantee, which is exactly
why the selfie check-in exists alongside it: a timestamped, EXIF-stripped
photo captured in-app at the moment of arrival is harder to fake at scale
than a spoofed coordinate, and the combination (geofence **and** selfie)
is what the missed-checkin escalation in Live Trip Mode keys off, not
either signal alone.

```sql
meetup_feedback
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  meetup_id       UUID NOT NULL REFERENCES meetups(id)
  user_id         UUID NOT NULL REFERENCES users(id)
  comfort_rating  SMALLINT NOT NULL CHECK (comfort_rating BETWEEN 1 AND 5)
  notes           TEXT
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()

  UNIQUE (meetup_id, user_id)
```
`comfort_rating` is **private** — this is not a review. It is never joined
into `reviews`, `trust_score_events`, or any profile-facing query, and the
`trust` module cannot reach this table at all (see Module boundaries). The
only thing that ever reads it is the trip-lock gate: if either party's
rating is below a threshold (e.g. 3), the match silently stops
progressing — the *other* party's next status check returns a generic
"this match didn't proceed," with no reason and no indication of who
declined. Silent decline removes both retaliation risk and any pressure to
justify withdrawing — the same principle as the cancellation safety
carve-out in `trip_members`, applied one rung earlier in the ladder.

Trip lock gate (service-layer rule, cross-referencing `trip_members`,
`meetups`, `meetup_feedback`): `trip_members.status` may transition to
`LOCKED` only when the `meetups` row for `(trip_id, requester_user_id)` has
`status = 'COMPLETED'` **and** no `meetup_feedback` row for that meetup has
`comfort_rating` below the threshold.

### 6. Settlement & Ledger (obligations, not custody)

No table in this section ever represents platform-held money. A
`ledger_entries` row only ever records a **confirmed, already-settled
obligation between two users** — the money itself moved over UPI, outside
the platform, before the row is written.

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
`settled_at` is set in bulk, across every `expense_shares` row for a trip,
the moment every `settlements` row for that trip reaches `CONFIRMED` (see
below) — debt simplification nets balances across the whole trip, so
individual shares don't map one-to-one to individual settlements.

#### Debt simplification algorithm

Splitting every expense pairwise ("Alice owes Bob ₹200, Bob owes Carol
₹150, Carol owes Alice ₹50, ...") and settling each pair individually
needs up to `N·(N-1)/2` transfers for `N` trip members. Instead we compute
each member's **net balance** once (total they paid out minus total their
share came to) and emit the minimum number of transfers that zeroes every
balance — at most `N-1` transfers, because the walk below retires at least
one member per step:

```
computeSettlements(expenses, shares) -> List<SettlementProposal>

1. net[user] = Σ(amount paid by user as an expense's payer)
             − Σ(share_amount owed by user across all expense_shares)
2. creditors = max-heap of {user, net} where net > 0
   debtors   = max-heap of {user, net} where net < 0 (ordered by |net|)
3. while creditors and debtors are both non-empty:
     c = pop largest creditor, d = pop largest debtor
     amount = min(c.net, |d.net|)
     emit SettlementProposal(from = d.user, to = c.user, amount)
     c.net -= amount; d.net += amount
     if c.net > 0: push c back        if d.net < 0: push d back
4. return emitted settlements
```
This is implemented as a **pure function** — no DB access, no side
effects, `List<ExpenseShare> -> List<SettlementProposal>` — specifically
so it can be property-tested with randomized inputs rather than only a
handful of hand-picked examples:
- **Conservation**: `Σ net[user] == 0` always, before and after — true by
  construction since every rupee paid is owed by someone.
- **Correctness**: applying every emitted settlement to the net balances
  leaves every balance at exactly 0.
- **Minimality**: the number of emitted settlements is `≤ N-1` where `N` is
  the count of users with a non-zero net balance.
- Complexity is `O(N log N)` (heap push/pop per settlement, at most `N-1`
  settlements), versus `O(N²)` for naive all-pairs settlement.

```sql
settlements
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  trip_id         UUID NOT NULL REFERENCES trips(id)
  from_user_id    UUID NOT NULL REFERENCES users(id)
  to_user_id      UUID NOT NULL REFERENCES users(id) CHECK (to_user_id <> from_user_id)
  amount          NUMERIC(12,2) NOT NULL CHECK (amount > 0)
  currency        VARCHAR(3) NOT NULL DEFAULT 'INR'
  status          VARCHAR(20) NOT NULL DEFAULT 'PROPOSED'
                    CHECK (status IN ('PROPOSED','MARKED_PAID','CONFIRMED','DISPUTED'))
  upi_deep_link   TEXT                      -- upi://pay?... built from to_user's upi_vpa, if they set one; null otherwise
  idempotency_key VARCHAR(100) NOT NULL UNIQUE
  version         BIGINT NOT NULL DEFAULT 0  -- @Version, optimistic lock
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_settlements_trip (trip_id)
    -- WHY: "show the settle-up screen for this trip" reads every
    -- settlement for the trip at once.
  INDEX idx_settlements_pending_by_payer (from_user_id, status) WHERE status IN ('PROPOSED','MARKED_PAID')
  INDEX idx_settlements_pending_by_payee (to_user_id, status) WHERE status IN ('MARKED_PAID')
    -- WHY: "settlements I need to act on" differs by role — a payer acts
    -- on PROPOSED (mark paid), a payee acts on MARKED_PAID (confirm).
    -- Two partial indexes because the action and the role differ.
```

```sql
settlement_state_transitions    -- APPEND-ONLY
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  settlement_id  UUID NOT NULL REFERENCES settlements(id)
  from_status    VARCHAR(20) NOT NULL
  to_status      VARCHAR(20) NOT NULL
  reason         VARCHAR(50) NOT NULL
  actor_id       UUID REFERENCES users(id)
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_settlement_transitions_settlement (settlement_id, created_at)
```

```sql
ledger_entries        -- APPEND-ONLY, no UPDATE/DELETE grants
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
  transaction_id    UUID NOT NULL          -- groups the two legs of one double-entry transaction
  account_id        UUID NOT NULL          -- a user_id directly: there is no custody account, see below
  entry_type        VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT'))
  amount            NUMERIC(12,2) NOT NULL CHECK (amount > 0)
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR'
  reference_type    VARCHAR(30) NOT NULL CHECK (reference_type IN ('SETTLEMENT_CONFIRMED'))
  reference_id      UUID NOT NULL          -- settlements.id
  idempotency_key   VARCHAR(100) NOT NULL UNIQUE
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_ledger_account_time (account_id, created_at)
    -- WHY: "my settled-obligation history across every trip" reads one
    -- user's entries in time order.
  INDEX idx_ledger_transaction (transaction_id)
    -- WHY: verifying a transaction's two legs balance (SUM(debits) =
    -- SUM(credits)) groups by this.
```
`account_id` is a `user_id`, not a wallet — there is no balance here that
can be withdrawn or spent; this is a bookkeeping double-entry of
obligations that have *already* been settled over UPI, kept so a user's
full settle-up history is queryable and auditable the same way real money
movement would be, without the platform ever being a party to the
transfer. `idempotency_key` still matters even without custody: a retried
confirm request must not double-write the same confirmed settlement into
someone's history.

#### Settlement flow & state machine

States: `PROPOSED → MARKED_PAID → CONFIRMED` (terminal, success) or
`→ DISPUTED` (terminal for the automated flow, resolved manually by an
admin outside this state machine — a disputed settlement is cancelled and
a new one proposed, the disputed row's history is never edited).

```
PROPOSED  --(payer marks paid)-->  MARKED_PAID  --(payee confirms)-->  CONFIRMED
   |                                    |
   +------------(either party disputes)-+------------------------> DISPUTED
```

The integrity property this must prove: **one side alone can never move
the ledger.**
- `PROPOSED` is created by a debt-simplification run (or an on-demand
  "settle up" request); `upi_deep_link` is built from `to_user_id`'s
  self-declared `upi_vpa` if they've set one, otherwise null and the UI
  tells the payer to ask for a UPI ID directly in chat.
- `MARKED_PAID` may only be set by `from_user_id` (the payer) — "I sent
  the money."
- `CONFIRMED` may only be set by `to_user_id` (the payee), and **only**
  from `MARKED_PAID` — "I received it." This is the one transition that
  writes `ledger_entries` (one DEBIT row for `from_user_id`, one CREDIT
  row for `to_user_id`, same `transaction_id`, with the idempotency key)
  and the matching `settlement_state_transitions` row, in one DB
  transaction, optimistically locked on `settlements.version`.
- `from_user_id` calling confirm on their own settlement (self-confirm) is
  rejected at the service layer regardless of what state it's in — the
  payer marking their own payment "confirmed" would let one side move the
  ledger alone, which is exactly the property under test.

Tests that must exist for this (not optional): confirming a `PROPOSED`
settlement that skipped `MARKED_PAID` is rejected; `from_user_id` calling
confirm is rejected with zero `ledger_entries` written; two concurrent
confirm calls on the same settlement produce exactly one `ledger_entries`
pair (optimistic lock + idempotency key), not two.

### 7. Live Trip Mode & Safety

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
The same `emergency_contact` this table holds is who gets notified by
`meetups.emergency_contact_notified_at` and by the 30/60-minute
missed-ping escalation in the meet-up protocol — one emergency contact
record, reused by both the pre-trip meet-up safety net and live-trip
check-ins, not two parallel "who do we call" lists.

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

### 8. Reviews & Trust Score

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
trusted to application code discipline. (This is a different, *public*
mechanism from `meetup_feedback`'s private comfort rating — double-blind
means "hidden until both submit, then both visible"; the meetup rating is
never visible to anyone but the rater and the trip-lock gate.)

```sql
trust_score_events
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id     UUID NOT NULL REFERENCES users(id)
  event_type  VARCHAR(30) NOT NULL CHECK (event_type IN
                ('VERIFICATION_TIER_CHANGE','REVIEW_VISIBLE','TRIP_COMPLETED','GHOST','RESPONSE_RATE_SAMPLE','VOUCH_PENALIZED'))
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

### 9. Platform (outbox, audit, compliance)

```sql
outbox_events
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
  aggregate_type VARCHAR(30) NOT NULL        -- e.g. 'TRIP', 'SETTLEMENT', 'REVIEW', 'MEETUP'
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
settlement/review state writes its row **and** an outbox row in the same
DB transaction (so the event can never be "lost" relative to the state
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
  event_type     VARCHAR(50) NOT NULL         -- AUTH_LOGIN, SETTLEMENT_CONFIRMED, ADMIN_SUSPEND, SOS_TRIGGERED, ...
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
A `SAFETY` cancellation in `trip_members` auto-inserts a `REPORT` row here
(`reason = 'SAFETY_CANCELLATION'`, `reporter_id` = the user who withdrew,
`reported_id` = the counterparty) — reusing the existing moderation-queue
and auto-suspend machinery instead of building a second one, so a pattern
of people feeling unsafe around the same counterparty surfaces to a
moderator the same way repeated harassment reports do, without ever
touching that counterparty's public trust score.

---

## Trust Score Engine spec

**Inputs** (each a row in `trust_score_events`, written by the owning
module's outbox consumer, never computed inline):
1. `VERIFICATION_TIER_CHANGE` — base points per tier: EMAIL=10, PHONE=25,
   SELFIE_MATCH=50 (cumulative tier, not additive — a user at
   SELFIE_MATCH gets 50, not 10+25+50). No `ID_DOCUMENT` tier exists —
   see "No government ID verification" above.
2. `REVIEW_VISIBLE` — a star rating (1-5) from a completed trip partner.
3. `TRIP_COMPLETED` — did the user actually show up and finish the trip.
4. `GHOST` — a `trip_members` row reaches `CANCELLED` with
   `cancellation_type = 'CONVENIENCE'`, or a locked member no-shows with no
   cancellation record at all. **Never** emitted for
   `cancellation_type = 'SAFETY'` — see the carve-out below.
5. `RESPONSE_RATE_SAMPLE` — periodic sample of (messages replied to /
   messages received) in chat.
6. `VOUCH_PENALIZED` — a user this account actively vouches for
   (`vouches.voucher_user_id = this user`) gets suspended/banned, or
   crosses the auto-suspend report threshold. See "Vouching" below.

**Safety carve-out** (a deliberate exception to "ghosting costs trust",
not an oversight): if withdrawing from a trip costs something, people stay
in situations they feel unsafe in rather than pay that cost — exactly
backwards for a platform whose reason to exist is traveler safety. A
`SAFETY` cancellation is free: no `GHOST` event, no trust score impact,
full stop. The service method that records it cannot reach the `GHOST`
code path at all (see `trip_members` above); it only ever raises a
`blocks_reports` flag against the counterparty for a moderator to look at.
The safe exit must always be the cheapest action available, or the trust
score itself becomes a safety hazard.

**Vouching**: a `SELFIE_MATCH`-tier user can vouch for another user,
putting a small piece of their *own* trust score at stake — "skin in the
game" is what makes a vouch worth more than a free click. Two effects,
deliberately kept simple rather than coupling the two users' scores
tightly:
- **Bonus** (on the vouched user): `+2` points to `verification_component`
  per active voucher, capped at `+10` total (5+ active vouchers maxes it
  out). Computed live from `COUNT(*)` on `idx_vouches_vouched_active` at
  recompute time, not stored as a historical event.
- **Penalty** (on the voucher): when a user they actively vouch for is
  suspended/banned or crosses the auto-suspend report threshold, a
  `VOUCH_PENALIZED` event is emitted for every active voucher found via
  `idx_vouches_vouched_active` on the now-suspended user. Each event
  applies a small, fixed, time-decaying deduction (not proportional to
  whatever score the vouched user actually lost — deliberately simpler so
  one user's bad outcome can't cascade unpredictably through everyone who
  vouched for them), with total vouch-penalty deductions for any one
  voucher capped so it can never alone move someone from a high score to
  a low one.

**Formula**, recomputed by a scheduler that drains unprocessed
`trust_score_events` per user (never inline in a request):

```
weighted_sum =
    0.30 * verification_component        -- includes the vouch bonus, capped at +10
  + 0.30 * bayesian_peer_rating_component
  + 0.20 * completion_rate_component     -- trips completed / trips committed to
  + 0.15 * ghost_penalty_component       -- 100 - (ghosts / commitments * 100)
  + 0.05 * response_rate_component

score = clamp(0, 100, weighted_sum - vouch_penalty_deduction)
```
`vouch_penalty_deduction` is applied after the weighted sum, not folded in
as a sixth weight — adding another weighted component would mean
re-deriving the other five weights to keep them summing to 1.0 every time
the vouching rules change. A flat, capped, post-hoc deduction keeps the
five core weights stable and keeps the vouch mechanic auditable on its
own: "score was 72, minus a 4-point vouch penalty, net 68" is a sentence a
user can be shown directly.

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
