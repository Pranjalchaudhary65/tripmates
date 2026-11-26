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
| `identity` | users, user_encryption_keys, identity_verifications, vouches, user_blocks, consent_records, otp_codes, totp_backup_codes, refresh_token_families, login_history, emergency_contacts, upi_ids; publishes the read-only views user_public_facts and user_block_facts for other modules' SQL | — (foundation) |
| `vibe` | vibe_profiles, deal_breakers, vibe_quiz_drafts; publishes the read-only view deal_breaker_facts for matching | identity |
| `trips` | trips, trip_members, match_score_cache; publishes the read-only views trip_facts and trip_member_facts for other modules' SQL | identity, vibe |
| `chat` | conversations, chat_messages, moderation_flags | identity, trips |
| `meetup` | meetups, meetup_checkins, meetup_feedback, verified_venues, video_call_confirmations | identity, trips |
| `settlement` | trip_ledgers, ledger_transactions, ledger_entries, settlements, settlement_state_transitions, settlement_overdue_notices | identity, trips |
| `safety` | itinerary_shares, checkins, sos_events | identity, trips |
| `trust` | reviews, trust_score_events, trust_score_snapshots, trust_score_state | identity, trips (settlement, meet-up and chat reach it only through the outbox) |
| `moderation` | reports | identity, trips |
| `compliance` | data_requests | identity, platform (every module contributes to an export through platform's `PersonalDataContributor`) |
| `platform` | outbox_events, audit_log, audit_integrity_checks | all (infra module, no business logic) |

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
                            CHECK (date_of_birth <= (now() AT TIME ZONE 'Asia/Kolkata')::date - INTERVAL '18 years')
  gender                  VARCHAR(20) NOT NULL CHECK (gender IN ('MALE','FEMALE','NON_BINARY','PREFER_NOT_TO_SAY'))
  phone_ciphertext        BYTEA                         -- AES-GCM envelope-encrypted
  phone_blind_index       VARCHAR(64)                   -- HMAC-SHA256(phone), indexed, for uniqueness/lookup without decrypting
  upi_vpa                 VARCHAR(100)                  -- optional, self-declared, user@bank — only used to build a settlement deep link
  profile_photo_key       TEXT                          -- object storage key, never a public URL
  email_verified_at       TIMESTAMPTZ
  phone_verified_at       TIMESTAMPTZ
  id_verification_tier    VARCHAR(20) NOT NULL DEFAULT 'NONE'
                            CHECK (id_verification_tier IN ('NONE','EMAIL','PHONE','SELFIE_MATCH'))
  totp_secret_ciphertext  BYTEA                         -- AES-GCM under the user's DEK, see user_encryption_keys
  totp_enabled            BOOLEAN NOT NULL DEFAULT FALSE
  totp_last_used_step     BIGINT                        -- RFC 6238 time step of the newest accepted code, refuses replays
  trust_score             NUMERIC(5,2) NOT NULL DEFAULT 0   -- denormalized read cache, owned by trust module
  trust_score_updated_at  TIMESTAMPTZ
  account_status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (account_status IN ('ACTIVE','SUSPENDED','BANNED','DELETED'))
  role                    VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN'))
  encryption_key_id       UUID                          -- nullable; FK below
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
  deleted_at              TIMESTAMPTZ

  FOREIGN KEY (encryption_key_id, id) REFERENCES user_encryption_keys (id, user_id)
    -- WHY: users and user_encryption_keys reference each other. The cycle
    -- is broken by keeping encryption_key_id nullable: the user row is
    -- inserted first (no key yet), the key row second, and the user row
    -- updated to point at it. The FK is added by ALTER TABLE after both
    -- tables exist. Composite rather than encryption_key_id alone, so a
    -- user can only ever point at a key whose user_id is that same user —
    -- a single-column FK would accept another user's key id. MATCH SIMPLE
    -- skips the check while encryption_key_id is NULL.

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
  UNIQUE INDEX idx_users_phone_verified_unique (phone_blind_index)
    WHERE phone_verified_at IS NOT NULL AND deleted_at IS NULL
    -- WHY: one live account per verified number; an unconfirmed number may
    -- sit on several accounts, since nobody has proven they hold it.
  CHECK ((phone_ciphertext IS NULL) = (phone_blind_index IS NULL))
  CHECK (phone_ciphertext IS NULL OR encryption_key_id IS NOT NULL)
  CHECK (phone_verified_at IS NULL OR phone_ciphertext IS NOT NULL)
  CHECK (id_verification_tier = 'NONE' OR (email_verified_at IS NOT NULL
         AND (tier NOT IN ('PHONE','SELFIE_MATCH') OR phone_verified_at IS NOT NULL)
         AND (tier <> 'SELFIE_MATCH' OR profile_photo_key IS NOT NULL)))   -- ck_users_tier_evidence
    -- WHY: the tier is only ever as high as the evidence on the row.
  INDEX idx_users_account_status (account_status) WHERE account_status <> 'ACTIVE'
    -- WHY: admin moderation queries ("show me suspended users") filter on
    -- this constantly; partial index keeps it tiny since >99% of rows are ACTIVE.
  CHECK (role <> 'ADMIN' OR totp_enabled)
    -- WHY: an admin account without a second factor cannot exist, so no
    -- code path (a forgotten check, a hand-run UPDATE) can create one.
  CHECK (NOT totp_enabled OR totp_secret_ciphertext IS NOT NULL)
  CHECK (totp_secret_ciphertext IS NULL OR encryption_key_id IS NOT NULL)
    -- WHY: an encrypted field is useless without the key it names.
```
`totp_last_used_step` was added while building 2FA: without it, a code seen
over someone's shoulder (or captured by a phishing proxy) stays usable for
the whole ±30-second window. See "Two-factor authentication" below.
The 18+ `CHECK` is a hard DB-level floor, not just a signup-form
validation: a platform whose entire purpose is introducing strangers to
each other must never admit a minor, and that guarantee can't live only
in a form that a direct API call bypasses. The service layer duplicates
the check (for a clear, immediate error message); the constraint is the
backstop if it doesn't. See `THREAT_MODEL.md`.

`now()` is not immutable, and PostgreSQL treats every CHECK as if it were:
the condition is evaluated only when a row is inserted or updated, never
re-checked as time passes, and re-evaluated in full when the table is
reloaded by a dump/restore. That is safe here only because the condition is
monotonic: a date of birth that is 18+ years in the past stays so forever,
so a row that passed once passes on every later UPDATE and on every
restore. (A CHECK like `expires_at > now()` would be the dangerous kind — a
restore a week later would fail.) "Today" is pinned to IST in the
constraint, and the service's `AgePolicy` uses the same zone and the same
`dob <= today - 18 years` arithmetic (which differs from `dob + 18 years`
on 29 February), so the two never disagree on a boundary day because of the
session's `TimeZone` setting.

```sql
user_encryption_keys
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id       UUID NOT NULL REFERENCES users(id)
  wrapped_dek   BYTEA NOT NULL      -- per-user Data Encryption Key, wrapped by the app's master Key Encryption Key (env/secret manager)
  active        BOOLEAN NOT NULL DEFAULT TRUE
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  revoked_at    TIMESTAMPTZ

  UNIQUE (id, user_id)
    -- WHY: target of the composite FK from users.encryption_key_id.
  UNIQUE INDEX idx_uek_user_active (user_id) WHERE active
    -- WHY: envelope-decryption of any PII field for a user first fetches
    -- the one active DEK; this index makes that a single-row lookup, and
    -- UNIQUE makes "the" active key unambiguous — two active keys for one
    -- user would leave it undefined which one encrypts new data.
  CHECK (active = (revoked_at IS NULL))
  CHECK (active = (octet_length(wrapped_dek) > 0))
    -- WHY: a revoked key is a shredded key. Flipping a flag alone would
    -- leave the wrapped DEK in the row, and anyone holding the KEK could
    -- still unwrap it.
```
This table is the hook for crypto-shredding: GDPR/DPDP-style deletion sets
`active = FALSE, revoked_at = now()` and overwrites `wrapped_dek` with zero
bytes (the second CHECK makes that the only way to revoke). There is no DEK
cache to purge: a DEK is unwrapped per use and its bytes zeroed afterwards.
Every ciphertext column encrypted under that DEK becomes permanently
unreadable even though the row still physically exists — which is what lets
us satisfy "delete my data" without breaking foreign keys in the
ledger/audit tables that must legally stay append-only. Database backups
taken before the shred still hold the wrapped DEK, so backup retention must
be no longer than the deletion SLA.

**Envelope encryption.** The KEK is 32 random bytes from `DATA_ENCRYPTION_KEK`
(base64, no default; the app refuses to start without it). A user's DEK (32
random bytes) is created the first time one of their fields is encrypted, in
the same transaction, and `users.encryption_key_id` is pointed at it. Both
layers are AES-256-GCM with a fresh 96-bit nonce per encryption:

- `wrapped_dek` = `0x01 | KEK fingerprint (first 8 bytes of SHA-256(KEK)) |
  nonce | ciphertext+tag`, additional data `user_encryption_keys|{id}|{user_id}`.
- a field = `0x01 | nonce | ciphertext+tag`, additional data
  `{table}.{column}|{user_id}`.

The additional data pins each ciphertext to its row and column: a value
copied into another user's row, or into another encrypted column, fails
authentication instead of decrypting. The fingerprint makes a wrong KEK fail
loudly at unwrap instead of as a generic tag mismatch, and leaves room to
rotate the KEK by re-wrapping DEKs (the field ciphertexts never change).

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
  rejection_reason    VARCHAR(30) CHECK (rejection_reason IN ('LIVENESS_FAILED','LOW_SIMILARITY',
                        'REVIEWER_REJECTED','PROFILE_PHOTO_CHANGED','REVIEW_EXPIRED'))
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
  reviewed_at         TIMESTAMPTZ

  CHECK (tier <> 'PHONE' OR (similarity_score, selfie_image_key, purge_due_at, purged_at all NULL))
  CHECK (tier <> 'SELFIE_MATCH' OR purge_due_at IS NOT NULL)
  CHECK (purged_at IS NULL OR selfie_image_key IS NULL)
  CHECK ((status = 'PENDING') = (reviewed_at IS NULL))
  CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
  CHECK (reviewer_id IS NULL OR reviewer_id <> user_id)
  INDEX idx_idverif_user_tier_status (user_id, tier, status)
    -- WHY: "does this user have a pending SELFIE_MATCH review" and the
    -- verification centre's "newest attempt" both hit this.
  INDEX idx_idverif_purge_due (purge_due_at) WHERE selfie_image_key IS NOT NULL
    -- WHY: the scheduled purge job's only query — rows still holding an
    -- image key whose retention window has passed, regardless of status.
  UNIQUE INDEX idx_idverif_one_pending_selfie (user_id) WHERE tier = 'SELFIE_MATCH' AND status = 'PENDING'
    -- WHY: two submissions racing each other cannot both land in the queue.
  INDEX idx_idverif_pending_queue (created_at) WHERE tier = 'SELFIE_MATCH' AND status = 'PENDING'
    -- WHY: the admin review queue, oldest (closest to purge) first.
```
`rejection_reason` was first designed as free `TEXT`. It became a fixed
list while building the review queue: whatever a reviewer typed would be
shown on the user's screen, and a fixed list is the only way to be sure
nothing a moderator writes (or pastes) reaches a user. The CHECKs, the
one-pending index and the queue index were added at the same time.
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

Because of `UNIQUE (voucher_user_id, vouched_user_id)` a pair has one row
for good: withdrawing sets `revoked_at`, and vouching again for the same
person reopens that row with a new `created_at`. A vouch is withdrawn by its
own ID, and to anyone but its voucher that ID answers 404. The voucher sees
only the first name of the person they vouch for; the vouched person sees a
count, not who.

**Verification tiers, as built.** Each tier needs the one below it:
EMAIL (verified email) → PHONE (a code sent to the number confirmed) →
SELFIE_MATCH (a live selfie matched against the profile photo). The tier
column never runs ahead of the evidence on the row; `ck_users_tier_evidence`
refuses PHONE without `phone_verified_at` and SELFIE_MATCH without a
`profile_photo_key`, whatever writes it. Every tier change writes a
`VERIFICATION_TIER_CHANGE` outbox event in the same transaction (email
verification included).

- **Phone.** The number is normalised to E.164 (a 10-digit number starting
  6-9 is read as Indian), stored as `phone_ciphertext` under the user's DEK
  (`users.phone_ciphertext|{user_id}` additional data) and as
  `phone_blind_index` = HMAC-SHA256 under `PHONE_BLIND_INDEX_KEY`, a key of
  its own. Codes reuse `otp_codes` with `channel = 'PHONE'` and the email
  code rules. A code is sent to any well-formed number; only after the right
  code comes back is the caller told the number is verified on another
  account (`phone_in_use`), so the route is no lookup of registered numbers.
  `UNIQUE INDEX idx_users_phone_verified_unique (phone_blind_index) WHERE
  phone_verified_at IS NOT NULL AND deleted_at IS NULL` settles two accounts
  confirming one number at the same moment. Delivery is a `PhoneCodeSender`;
  the default `mailbox` sender puts the "SMS" in the account's email inbox
  so the demo needs no SMS account, and startup warns that it proves nothing
  about the phone. A verified number cannot be replaced yet.
- **Profile photo and selfie images.** Every upload is checked by magic
  bytes (JPEG or PNG only), size (5 MB, refused by the multipart limit
  before it is read) and header dimensions (64-4096 px, read before any
  pixel is decoded), then decoded with metadata ignored and re-encoded as a
  new JPEG, at most 1280 px on the long side. The original bytes are never
  stored, so EXIF (GPS included) cannot survive. Files go to an
  `ObjectStorage` (local disk under `uploads/` by default) under random
  `profile-photos/{uuid}.jpg` / `selfies/{uuid}.jpg` keys. They are reached
  only through signed links: `/api/v1/media/{token}`, the token being
  `base64url(key|expiry).base64url(HMAC-SHA256)` under
  `MEDIA_URL_SIGNING_KEY`, valid 5 minutes, handed only to the owner (their
  profile photo) or a reviewer (a flagged selfie and the photo beside it).
  Replacing the profile photo drops a SELFIE_MATCH account to PHONE and
  withdraws a pending selfie (`PROFILE_PHOTO_CHANGED`): both were judged
  against a picture that is no longer shown.
- **Selfie check.** `POST /verification/selfie/challenge` picks a prompt
  (turn left/right, smile, blink) and stores a single-use, 2-minute
  challenge in Redis (`selfie-check:{sha256(id)}`), bound to the account.
  The client sends two frames captured in the app: one looking at the
  camera, one following the prompt. A `LivenessVerifier` (outside any
  transaction, since a real one is a network call) returns `live` and a 0-1
  `similarity`; not live is REJECTED (`LIVENESS_FAILED`), similarity ≥ 0.85
  APPROVED, below 0.60 REJECTED (`LOW_SIMILARITY`), in between PENDING for a
  reviewer. Only the neutral frame is stored; the score is never shown to
  the user, since an exact number lets someone tune a borrowed photo until
  it passes. Five submissions per account per day. The default `mock`
  verifier compares difference hashes of the images, not faces, and says so
  at startup.
- **Purge.** A scheduled job (hourly) deletes every image past
  `purge_due_at`, file first and row second, rows claimed with `FOR UPDATE
  SKIP LOCKED`. A selfie still PENDING at that point is rejected as
  `REVIEW_EXPIRED`: nothing may stay in the queue without its evidence.
- **Review queue.** `/admin/verifications` lists PENDING selfies oldest
  first; a decision is APPROVE or REJECT only (no free text), the owner's
  row is locked before the verification's (the order every writer uses),
  and nobody can decide their own (`ck_idverif_reviewer_not_self` backs the
  service check).

**Outbox and append-only, settled (V20).** An event's *content* is
append-only; the relay's *bookkeeping* is not, but it can only move
forward. A trigger refuses DELETE and TRUNCATE outright, and any UPDATE
that touches `aggregate_type`, `aggregate_id`, `event_type`, `payload` or
`created_at`, clears or moves `processed_at` once set, or lowers
`attempts`. A new `dead_at` marks an event given up on after
`max-attempts` failures (`ck_outbox_settled_once`: never both). I chose
this over a separate `outbox_deliveries` table because the relay's one
query stays a partial-index scan (`idx_outbox_pending`) instead of an
anti-join that grows with every event ever written, and what append-only
is for, history nobody can rewrite, is still held by the database for
every role.

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

  CHECK (max_attempts > 0 AND attempt_count BETWEEN 0 AND max_attempts)
  INDEX idx_otp_user_purpose_active (user_id, purpose) WHERE consumed_at IS NULL
    -- WHY: "is there already a live OTP for this purpose" is checked on
    -- every resend request to enforce the resend throttle; partial index
    -- skips the (large, over time) consumed rows entirely.
```
`code_hash` is HMAC-SHA256 over `user_id|purpose|code` under a server-side
key (`OTP_HMAC_KEY`), not a plain hash: a six-digit code has only a million
values, so an unkeyed hash of every one can be computed in under a second
by anyone holding a copy of the table.

Email verification flow: the `otp_codes.id` of the code just issued is
returned to the registering client as its `verificationId`, and redeeming
needs both that id and the emailed code. Issuing a code supersedes (expires)
the previous one for that user and purpose. Re-registering an address that
is still unverified replaces its credentials and issues a new code — no one
has proven ownership yet, so the latest attempt wins — while the id binding
means a victim who types in a code can only ever verify the registration
*they* made, never one an attacker made with their own password
(pre-registration hijacking). A verified address is never touched by a new
registration; the API answers identically with a random decoy id and the
owner is emailed a notice. Limits: 5 attempts per code, 10-minute expiry,
60-second resend cooldown, 5 codes per account per 24 hours.

```sql
totp_backup_codes
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id     UUID NOT NULL REFERENCES users(id)
  code_hash   TEXT NOT NULL             -- HMAC-SHA256 under OTP_HMAC_KEY, hex
  used_at     TIMESTAMPTZ
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()

  CHECK (code_hash ~ '^[0-9a-f]{64}$')
  INDEX idx_backup_codes_user_unused (user_id) WHERE used_at IS NULL
```
Ten codes per set, each 10 characters from a 32-letter alphabet without
look-alikes (50 random bits), shown as `XXXXX-XXXXX`. `code_hash` is
HMAC-SHA256 over `totp-backup|user_id|code` under the same server key as
`otp_codes` (the prefix keeps the two uses apart). 50 bits is too many to
enumerate per user once a key is involved, and a keyed MAC keeps each check
to microseconds; Argon2id over ten rows would cost half a second per login
attempt. Regenerating deletes the old set in the same transaction that
inserts the new one; disabling 2FA deletes it.

```sql
refresh_token_families
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id          UUID NOT NULL REFERENCES users(id)
  device_name      VARCHAR(64) NOT NULL   -- server-derived label ("Firefox on Linux") from a fixed vocabulary, never client text
  ip_address       INET NOT NULL          -- where the session was signed in
  user_agent       TEXT                   -- truncated to 512 chars; client-controlled, stored for audit, never returned
  last_ip_address  INET NOT NULL          -- where the newest refresh came from
  last_used_at     TIMESTAMPTZ NOT NULL   -- sign-in or newest refresh
  expires_at       TIMESTAMPTZ NOT NULL   -- created_at + 30 days, absolute: rotation never extends it
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
  revoked_at       TIMESTAMPTZ
  revoked_reason   VARCHAR(30) CHECK (revoked_reason IN ('LOGOUT','REUSE_DETECTED','REVOKE_ALL_DEVICES',
                     'ADMIN','SECURITY_CHANGE'))
  second_factor_verified BOOLEAN NOT NULL DEFAULT FALSE  -- the login passed TOTP or a backup code

  CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
  CHECK (expires_at > created_at)
  INDEX idx_rtf_user_active (user_id) WHERE revoked_at IS NULL
    -- WHY: "revoke all devices" and "list my active sessions" both need
    -- every live family for a user; partial index excludes dead families.
```
One family is one signed-in session: everything a login issues descends from
it, and it is what "list my sessions" shows. An earlier draft of this
document had a separate `active_sessions` table, 1:1 with this one and
repeating its `revoked_at`. It was dropped while building token issuing: two
copies of "is this session revoked" can disagree after a partial failure,
and a session screen that shows a device as signed out while its token still
works is worse than no screen at all. `last_used_at` and `last_ip_address`
moved here instead. `device_name`, `last_ip_address`, `last_used_at`,
`expires_at` and `updated_at` were added at the same time.
`second_factor_verified` and the `SECURITY_CHANGE` reason came with 2FA: the
`amr` claim has to survive every refresh, so how the session was signed in is
recorded on the session itself, and turning 2FA on or off ends the account's
other sessions under a reason of its own.

**Refresh tokens.** The cookie value is `{family_id}.{secret}`, the secret
being 32 random bytes as base64url. Only SHA-256 of the whole value is ever
stored; as with the device cookie, 256 random bits leave no candidate set to
hash, so a key adds nothing.

Redis holds the rotating state in one hash per family,
`refresh:{family_id} -> {current, previous, rotatedAt}`, expiring with the
family. Postgres is the source of truth for whether a family is alive
(revocation is durable there, and a lost Redis key can only end a session,
never revive one); Redis is the source of truth for which token in the
family is current, and for "is this session alive" on every authenticated
request. A refresh must pass both. Rotation is one Lua script, so two
refreshes can never both rotate the same token:

- presented hash = `current` → rotate (`previous` = old, `current` = new,
  `rotatedAt` = now) and issue a new cookie;
- presented hash = `previous` and `rotatedAt` is at most 10 seconds ago →
  a second tab racing the first: issue an access token only, no new cookie
  (the browser already holds, or is about to receive, the new one);
- anything else on a live key → **reuse**: the script deletes the key, and
  the family is revoked in Postgres with `REUSE_DETECTED`. One replayed
  token kills every token descended from it, and every access token minted
  from the family stops working at once (see below).

Logout, "revoke all devices" and reuse revoke in Postgres and delete the
Redis keys inside one transaction: if Redis cannot be reached, the
transaction rolls back and the client gets `503`, never a success that did
not happen.

**Access tokens.** HS256 JWTs, 15 minutes, never longer than the family
they belong to, with header `typ: at+jwt` and a `kid`. Claims: `iss`,
`aud`, `sub` (user id), `sid` (family id), `role`, `amr`, `iat`, `exp`,
`jti`. `amr` (RFC 8176) is `["pwd"]`, or `["pwd","otp"]` when the session's
login passed a second factor; it is required, must contain `pwd`, and may
hold no other value. Every authenticated request checks that `refresh:{sid}` still exists
in Redis, so a revoked session's access tokens stop working immediately
rather than at expiry, and fails closed with `503` when Redis cannot answer.

**Refresh cookie.** `tm_refresh`, `HttpOnly; Secure; SameSite=Strict`,
`Path=/api/v1/auth/refresh` (no other endpoint ever receives it), `Max-Age`
= the family's remaining lifetime. `/auth/refresh` also requires the header
`X-TripMates-CSRF: 1`: a cross-origin page cannot send a custom header
without a CORS preflight, which only allowlisted origins pass.

```sql
login_history
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
  user_id         UUID REFERENCES users(id)        -- nullable: failed login with unknown email still logged
  email_attempted CITEXT NOT NULL
  ip_address      INET NOT NULL
  user_agent      TEXT                             -- truncated to 512 chars; client-controlled
  device_id_hash  VARCHAR(64)                      -- SHA-256 of the device cookie, see below
  success         BOOLEAN NOT NULL
  failure_reason  VARCHAR(30) CHECK (failure_reason IN ('UNKNOWN_EMAIL','BAD_PASSWORD',
                    'EMAIL_NOT_VERIFIED','ACCOUNT_DISABLED','LOCKED','CAPTCHA_FAILED',
                    'BAD_SECOND_FACTOR'))
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()

  CHECK (success = (failure_reason IS NULL))
  CHECK (NOT success OR user_id IS NOT NULL)
  INDEX idx_login_history_user_time (user_id, created_at DESC)
    -- WHY: "show my login history" wants the most recent rows for a user,
    -- newest first.
  INDEX idx_login_history_ip_time (ip_address, created_at)
    -- WHY: reviewing recent failures by IP across all accounts
    -- (credential-stuffing detection). Live rate limiting does not query
    -- this table; its counters are in Redis, see "Login" below.
  INDEX idx_login_history_user_device (user_id, device_id_hash) WHERE success
    -- WHY: "has this browser signed in to this account before" decides both
    -- the new-device alert and whether the browser gets its own lockout
    -- counter; it is asked on every login that presents a device cookie.
```
`device_id_hash` was added during the login build. The columns first designed
for this table could not answer "is this a new device": `user_agent` alone
gives false alerts on every browser update, and an attacker who copies the
victim's User-Agent string gets no alert at all. A random 256-bit device ID
in an `HttpOnly; Secure; SameSite=Strict` cookie (path `/api/v1/auth`,
400 days) is something an attacker cannot copy without already owning the
victim's browser. Only its SHA-256 is stored; with 256 random bits there is
no small candidate set to hash and compare, so a key is not needed (unlike
the six-digit OTPs above).

**Login.** An unknown email, a wrong password, an unverified email, a
disabled account and a locked account all get the same `401
invalid_credentials` with the same headers. Each does the same work in the
same order: one account lookup, one lockout charge, and exactly one
Argon2id verification (against a dummy hash made with the same encoder when
the email has no account, and against the real hash even when the account is
locked). `login_history` alone records which case it was. A success opens a
refresh token family and answers `{status: "AUTHENTICATED", session:
{accessToken, tokenType, expiresIn, user}}` with the refresh and device
cookies. `status` is the extension point for a second factor: an account
that needs one gets a different status and a challenge in a sibling field,
and no session until the challenge is passed.

Every counter is keyed on SHA-256 of the lower-cased typed email, not on the
account, so an email with no account is rate-limited, asked for a CAPTCHA and
"locked" on exactly the same schedule as one that has an account. A browser
whose device cookie already has a successful login to this account gets its
own counter key (`email-hash:device:device-hash`) instead of the shared one.
That is what keeps lockout from becoming a weapon: someone guessing from
elsewhere can lock the account for unknown browsers, but not for the
victim's own browser.

- **Rate limits** (Bucket4j token buckets in Redis, continuous refill):
  login 20 per 10 min per IP and 10 per 10 min per email; register 10 per
  hour, verify-email 20 per 10 min and resend 10 per hour, per IP. IPv6 is
  counted per /64. Over the limit gives `429` + `Retry-After`, in the usual
  RFC 9457 body.
- **CAPTCHA**: after 3 consecutive failures on a counter key, the
  `CaptchaVerifier` must accept the request's `captchaToken`, or it gets
  `401 captcha_required`. A refused CAPTCHA is not charged as a failure. The
  default provider (`none`) accepts everything.
- **Lockout**: each attempt is charged *before* its password is checked,
  atomically in a Redis Lua script, so parallel guesses cannot get past the
  count. The attempt that brings the count to 5 arms a 1-minute lock; each
  further failure (once the lock has ended) doubles it: 2, 4, 8 minutes,
  capped at 15. Attempts refused while locked are not counted, so the lock
  cannot be held at the cap by one request a minute. A success clears the
  count, and the count is dropped 24 hours after the last failure.
- **Redis unavailable**: login, register, verify-email and resend fail
  **closed** with `503` + `Retry-After`. These limits are the main defence
  against guessing, and from token issuing onwards sessions live in Redis
  anyway.
- **Client IP**: Tomcat's RemoteIpValve, used only for proxies listed in
  `TRUSTED_PROXIES` (empty means trust no one), reading `X-Forwarded-For`
  from the right. Application code never reads the header itself.
- **New-device alert**: a successful login whose device hash has no earlier
  success for that user emails the owner (time in IST and IP, deliberately
  no User-Agent). The account's very first sign-in is not alerted.

The active sessions list is a view over `refresh_token_families` (live and
not expired), not a table of its own; see that table for why.

**Two-factor authentication (TOTP).** RFC 6238 with the parameters every
authenticator app defaults to: HMAC-SHA1, 6 digits, 30-second steps from the
Unix epoch, a 20-byte (160-bit, RFC 4226's recommended length) random secret
shown as unpadded Base32. Implemented on the JDK's `Mac`, no library.

- **Checking a code**: the steps one before, at and one after now are all
  computed and compared in constant time, every one of them, whatever matches
  first. A code is accepted only if its step is greater than
  `totp_last_used_step`, which is then set to it, under the user row lock: a
  code cannot be used twice, nor an older code after a newer one.
- **Setup** (`POST /auth/2fa/setup`, bearer + password): stores a fresh
  encrypted secret with `totp_enabled = false` and returns the secret and an
  `otpauth://` URI, once. Nothing changes for login yet. Calling it again
  before confirming replaces the pending secret (the first QR code stops
  working); calling it while 2FA is on is refused with `409`, since replacing
  a live secret is exactly what a stolen session would want to do.
- **Confirm** (`POST /auth/2fa/confirm`, bearer + a code from the pending
  secret): turns 2FA on, returns ten backup codes (the only time they are
  ever shown), ends every other session of the account (`SECURITY_CHANGE`)
  and emails the owner. The current session stays signed in, still `pwd`
  only: it was not signed in with a second factor.
- **Disable** (`POST /auth/2fa/disable`, bearer + password + a TOTP or backup
  code): clears the secret, the replay step and the backup codes, ends every
  other session, emails the owner. Refused with `403` for an ADMIN.
- **Regenerate backup codes** (`POST /auth/2fa/backup-codes`, bearer +
  password + a code): deletes the old set and returns a new one, emails the
  owner.
- The four routes share one bucket per user (10 per 15 minutes); a wrong
  password or code gets `400 reauthentication_failed` either way, never 401,
  which would send the client's interceptor off to refresh.

**Login with 2FA.** A correct password for an account with 2FA on (or any
ADMIN) clears the password lockout as before but opens no session, sets no
cookie and writes no success row. It answers `{status:
"SECOND_FACTOR_REQUIRED", secondFactor: {challengeId, expiresIn, methods}}`.
The challenge ID is 32 random bytes; Redis keeps
`login-2fa:{sha256(challengeId)} -> {userId, counterKey, attempts, expiresAt}`
for 5 minutes (checked against the application clock, the key TTL is only
cleanup). `POST /auth/login/second-factor` with the challenge ID and a TOTP or
backup code then:

1. charges the challenge one attempt, atomically in Lua; the sixth attempt
   finds it deleted (`401 login_challenge_expired`: start again with the
   password);
2. charges the second-factor lockout counter, `{password counter key}:second-factor`,
   with the same 5-failure, doubling lock as passwords, so a known
   password buys at most a handful of code guesses per quarter hour,
   whichever browser they come from, and the owner's known browser keeps its
   own counter;
3. checks the code under the user row lock (a backup code is marked used,
   the TOTP step recorded), then deletes the challenge, and opens the
   session in the same transaction, with `second_factor_verified = true`, so
   two racing submissions open one session between them.

A wrong code writes a `BAD_SECOND_FACTOR` row and answers `401
invalid_second_factor`. A backup code used to sign in emails the owner with
the number left.

**Admin routes.** `/api/v1/admin/**` needs `role = ADMIN` *and* `otp` in
`amr`, checked in the security filter chain, and the service re-reads the
caller's row (still ADMIN, still active, 2FA still on) because a token's role
can be up to 15 minutes old. Nothing in the API can grant ADMIN: the operator
runs an `UPDATE` by hand, and the CHECK above refuses it until the account has
2FA on.

**Authorization tests.** Every route the application maps is listed in
`EndpointAuthorizationMatrixIntegrationTest` with its access rule: public,
self-scoped (acts only on the caller's own account), or admin. The matrix
calls each one as an anonymous caller, as a second user, and as the owner,
and a coverage test fails the build when a mapped route has no entry. A
non-owner gets **404** on any resource addressed by ID, never 403: 403 would
confirm that the ID exists and belongs to somebody, and IDs leak (shared
links, logs, screenshots). Role-gated routes (admin) answer 403, because their
existence is public in the OpenAPI document anyway.

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

**As built.** Three additions while building the quiz:

- `vibe_profiles.quiz_version SMALLINT NOT NULL` says which question set
  `raw_answers` answered, so a re-score knows what it is reading.
  `vibe_profiles.adjusted_at TIMESTAMPTZ` is set when the user corrects a
  dimension by hand; a re-score leaves such a row alone rather than undo the
  user's own edit, and retaking the quiz clears it.
- `deal_breakers` gets `created_at`/`updated_at`, as every mutable table does.
- A new table holds a quiz in progress, so the 12-15 question form can be
  finished later or on another device. The client keeps nothing in browser
  storage (lint forbids it):

```sql
vibe_quiz_drafts
  user_id       UUID PRIMARY KEY REFERENCES users(id)
  quiz_version  SMALLINT NOT NULL
  answers       JSONB NOT NULL CHECK (jsonb_typeof(answers) = 'object')
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
```

**The quiz** (`vibe.service.VibeQuiz`, version 1) has 14 questions defined
on the server, each feeding exactly one dimension: two each for budget,
pace, wake time, planning, social energy and risk; one each for food and
expenses. Each option carries a number on its dimension's scale (enum
position, or 1-5); a dimension with two questions takes their mean, rounded
half up in exact arithmetic. A reversed statement ("I need time on my own")
has its options numbered in reverse, so scoring has no special cases. The
expense question names its category directly: the three ways of splitting
costs have no order to average. Option numbers are never sent to the client.
Submitting replaces the profile (and any hand adjustment) and deletes the
draft; every profile or deal-breaker change writes `VIBE_PROFILE_CHANGED` or
`DEAL_BREAKERS_CHANGED` to the outbox for match-cache invalidation.

**Habit deal-breakers** (`smoking`, `drinking`) are from the user's own side:
`NO` = "I want a trip without it", `OK` = "it will be part of my trip",
`EITHER` = no preference. Matching treats NO against OK as incompatible and
EITHER as compatible with both. The column names alone did not say this, so
it is written down here for the matching step.

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

**As built.** What the trips module added or settled while building it:

- **Coordinates.** `trips` stores `destination_latitude` and
  `destination_longitude` as plain numbers, and `destination_point` is a
  `GENERATED ALWAYS ... STORED` geography computed from them. The two can
  never disagree, the entity needs no spatial type, and the GIST index sits
  on the generated column as above.
- **More CHECKs.** Latitude/longitude ranges, a title of at least 3
  characters, `destination_radius_km` 0-500, `filled_slots >= 0`, and two
  that tie the status to the count: `OPEN` only with a slot left
  (`ck_trips_open_has_room`), `FULL` only with none (`ck_trips_full_has_none`).
- **`trip_members.version`** (optimistic lock on the request itself), so an
  accept and a reject of the same request cannot both commit, and
  `ck_trip_members_decided` (anything past `REQUESTED` has `decided_at`).
- **The host is not a `trip_members` row.** A row is always someone asking
  to join, or who has joined, another person's trip; `total_slots` counts
  companions, not the host.
- **`user_public_facts`**, a view identity publishes (`V8`): `user_id`,
  first name, `gender`, `id_verification_tier`, `trust_score` and an
  `active` flag. Trips (and matching) read other people only through it,
  never `users` itself, so no email, phone, date of birth, surname or photo
  key is reachable from a trips query. `gender` and `trust_score` are for
  matching filters and are never returned to another user.
- **Visibility.** A trip is visible to its host, to anyone with a request on
  it, and, while it is `OPEN` or `FULL` and its host is active, to every
  signed-in user. Anything else is the same 404 as an unknown ID.
- **Rules**, all in IST (`AgePolicy.LEGAL_ZONE`): a trip starts between today
  and 365 days ahead and lasts at most 60 days; a host has at most 10
  listed trips not yet over; an account has at most 20 requests waiting at
  once, counted under its user row lock; one request per person per trip,
  ever, so a rejection is final; a trip that has started takes no requests
  and no accepts (a waiting request can still be rejected). Browse lists
  `OPEN` trips by other, active hosts that have not started, soonest first,
  filtered by radius (`ST_DWithin`, at most 1000 km), dates and budget bands.
- **Slot claim.** Accepting loads the trip, calls `Trip.claimSlot` (which
  refuses a trip that is not `OPEN` or has no room) and commits; Hibernate's
  `@Version` makes the write `... WHERE id = ? AND version = ?`, and
  `ck_trips_filled_slots` is the database's last word on overfilling. A
  claim that loses the version race is retried from a fresh read in a new
  transaction, up to `slot-claim-attempts` (5) times, then answers
  `409 trip_busy`; a retry that finds the trip full answers `trip_full`.
  Each loss means another claim committed, so with N slots a claim loses at
  most N times. No lock is taken on the host's account for this, so the
  optimistic lock alone is what the concurrency test proves.
- **Routes.** `POST /trips`, `GET /trips` (browse), `GET /trips/hosted`,
  `GET /trips/{tripId}`, `POST /trips/{tripId}/join-requests`,
  `GET /join-requests/incoming` (host side, optional `tripId` and `status`),
  `GET /join-requests/outgoing`, `POST /join-requests/{requestId}/accept`
  and `/reject`. A decision names the request alone; anyone but its trip's
  host gets 404. No response carries another person's user ID: they are a
  first name and a verification tier.

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

**As built (matching).** Matching compares a viewer with a trip: the
trip's host's vibe profile and the trip's own budget band.

- **Two halves, in order.** First the deal-breakers, as SQL
  (`trips.repository.DealBreakerRules`), on both sides: smoking and drinking
  (`NO` against `OK` rules out, `EITHER` goes with both), gender (`SAME`
  needs both genders on record and equal, so `PREFER_NOT_TO_SAY` cannot meet
  a `SAME` preference on either side), and trust (each side's
  `users.trust_score` must reach the other's `min_trust_score`). A user with
  no `deal_breakers` row has no limits. Browse ANDs every rule into its WHERE
  clause, so a ruled-out trip is never listed; the detail screen selects each
  rule as a column and names the one that failed; a join request against a
  ruled-out trip is refused (`409 deal_breaker_conflict`), so a host's limits
  hold even for someone arriving through a link. Only then the soft score.
- **`deal_breaker_facts`**, a read-only view vibe publishes (`V10`), is how
  the trips module reads deal-breakers in SQL, the same pattern as
  `user_public_facts`. The soft score reads profiles through
  `vibe.service.MatchProfiles`, a module-to-module service no endpoint
  reaches.
- **Soft score** (`trips.service.MatchScorer`, pluggable; the default
  `RuleBasedMatchScorer` needs no API key, chosen by
  `tripmate.matching.scorer`). Weights: budget 20, pace 15, social energy 15,
  expenses 15, wake time 10, planning 10, risk 10, food 5 (sum 100). Each
  dimension scores a closeness 0-1: ordered values and the 1-5 scales by
  1 − distance / widest distance; expense styles have no order (same = 1,
  "whoever can" vs "take turns" = ½, "split exactly" vs either = 0). The
  score is the weighted sum, rounded half up. Closeness ≥ ⅔ is a match,
  ≤ ⅓ a mismatch, anything between is left out of the explanation. Deal-
  breakers never pass through the scorer, so no implementation of it can let
  a ruled-out trip through.
- **Explanation.** `matches[]` and `mismatches[]` of `{dimension, label}`,
  heaviest first, worded from the viewer's side ("You are both early
  birds.", "You are an early bird; the host is a night owl."). Every label is
  a fixed sentence built from enum values; none carries user text. A
  blocked match carries `blockers[]` of `{rule, label}` instead and no score.
  Statuses: `SCORED`, `BLOCKED`, `NEEDS_YOUR_PROFILE`, `HOST_HAS_NO_PROFILE`.
  The host gets no match on their own trip.
- **Cache columns added**: `scorer`, `viewer_profile_at`, `host_profile_at`
  (`V11`). Instead of relying on a delete arriving when a profile changes, a
  row is served only while its scorer and both stamps equal the current
  profiles' `updated_at`; otherwise it is recomputed and replaced
  (`INSERT ... ON CONFLICT DO UPDATE`). That makes invalidation exact at the
  moment the profile change commits, with no window in which a reader can
  write back a score computed from the old profile, and changing the scoring
  rules (a new `scorer` id) invalidates every row at once. Deal-breakers are
  never cached, so a changed limit, gender or trust score applies on the next
  read. Deleting stale rows by user (`idx_match_score_cache_user`) is left to
  the outbox relay, which `VIBE_PROFILE_CHANGED` already feeds; until it
  exists stale rows are simply overwritten when next read.
- **Responses.** `TripDetailResponse.match` carries the whole breakdown (one
  call for the trip screen); `TripSummaryResponse.matchScore` carries the
  score on browse cards, null where it cannot be scored.

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

**As built (chat).** What the chat module added or changed while building
it (`V12`, `V13`):

- **Opening.** A conversation is created when a host accepts a join
  request: trips publishes `CompanionAccepted` inside the accept's
  transaction and chat listens in that same transaction, so the
  conversation commits or rolls back with the acceptance and trips never
  calls chat. Companions accepted before chat existed got theirs in `V13`.
- **`trip_facts`**, a read-only view trips publishes (`V12`), is how chat
  (and later meet-ups) read a trip's title, place, dates and status in
  SQL, the same pattern as `user_public_facts`.
- **Bodies are encrypted, not plain `TEXT`.** `chat_messages.body` became
  `body_ciphertext BYTEA`: AES-GCM under the *sender's* data key, with the
  conversation ID and message number in the additional data, so a sealed
  body copied onto another row does not open. Crypto-shredding a user
  erases what they wrote; the other person's history then shows those
  messages as no longer available instead of failing. Pipeline before
  sealing: markup stripped by the OWASP sanitizer's HTML lexer (text nodes
  kept, every element and script/style content dropped), invisible and
  direction-changing characters removed, NFC; then contact details
  (emails, UPI IDs, links, @handles, 10+ digit numbers) replaced with
  `[contact hidden]`. The unredacted text is never stored.
- **Message numbers.** `seq` 1, 2, 3... per conversation, taken from
  `conversations.last_seq` under the conversation's row lock, replaces the
  `(conversation_id, created_at)` index with `UNIQUE (conversation_id, seq)`:
  gap-free, never shared, a history cursor and the basis of read markers
  (`host_read_seq`, `requester_read_seq`) and unread counts.
- **Idempotent send.** `client_message_id`, unique per conversation and
  sender: a retried send returns the stored message; the same ID with other
  text is refused (`409 message_id_reused`).
- **SYSTEM messages** carry a fixed `system_code` and no text, so nothing a
  system message says can be mistaken for either person's words.
  **Icebreakers are not messages at all**: `GET .../icebreakers` returns
  suggestions to the asker only, built by a pluggable `IcebreakerGenerator`
  (rule-based default from trip facts, `tripmate.chat.icebreakers`), each
  cleaned and redacted like a message; sending one is the user's own act.
- **Moderation.** A pluggable `MessageClassifier` (rule-based default,
  `tripmate.chat.classifier`) labels the cleaned text before redaction. A
  flagged message is still delivered with its flag, so both people see a
  warning; every label is a `moderation_flags` row; a label at or above
  `penalty-confidence` (0.95: money asked for, threats, sexual demands) is
  also a `CHAT_MESSAGE_FLAGGED` outbox event for the trust score. Nothing
  is ever auto-suspended on a pattern match.
- **Socket.** STOMP over WebSocket at `/api/v1/ws`, handshake origin-checked
  against the CORS allowlist and otherwise open, authenticated by the
  access token in the CONNECT frame. Clients may subscribe to
  `/user/queue/chat` only and send only typing notices; messages are sent
  over HTTP and pushed after commit to both people's own queues. Sockets
  are swept every 30 s and closed once their token expires or their session
  ends. The broker is in memory, so pushes reach sockets on the same
  instance only; running a second instance needs a shared relay (Redis
  pub/sub fanning each push out to every instance) first. Nothing is lost
  meanwhile: a client that misses a push refetches on reconnect.
- **Contact unlock.** `GET .../contact` returns the other person's name,
  email and verified phone only while `contact_unlocked_at` is set, which
  the trip-lock gate sets; otherwise `409 contact_locked`.

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

**As built (meet-up).** What the meet-up module added or settled (`V14`-`V16`):

- **A match is the accepted join request.** Every route is
  `/matches/{matchId}/...` where `matchId` is the `trip_members` row's ID;
  only its host and companion reach it (404 for anyone else). Trips exposes
  `TripMembership` (no endpoint) to read a match under its row lock, record
  the video call, lock, and release for safety; meet-up decides and calls it,
  so the gate lives in meet-up while trips still owns the rows. Lock and
  release publish `CompanionLocked` / `CompanionReleased`; chat listens and
  unlocks contact details or closes the conversation with a SYSTEM message.
  `trip_member_facts` (`V15`) lets chat link a conversation to its match.
- **Gate as built.** The trip locks when the meet-up completed and *both*
  ratings are in and at or above 3; a rating below 3 releases at once.
  Waiting for both gives each person the chance to decline. Every change to
  a match runs under its `trip_members` row lock, so two people rating at
  the same moment lock the trip exactly once (tested).
- **Silent stop.** A rating below 3, or a meet-up cancelled with type
  `SAFETY`, releases the companion: `trip_members` becomes `CANCELLED` /
  `SAFETY`, the slot is freed (a `FULL` trip reopens), the chat closes.
  Both people's match screen then shows only `DID_NOT_PROCEED`, the trip and
  the other's first name: no meet-up, no reason, no hint of who decided.
- **Safety is free by construction.** `MeetupTrustEvents` has exactly two
  methods, `noShow` and `lateConvenienceCancel`, both taking only IDs; there
  is no method that could carry a rating or a safety cancellation, and a
  test pins that list. `CONVENIENCE` cancellations allow a new proposal, so
  `UNIQUE (trip_id, requester_user_id)` became a partial unique index on
  active meet-ups (`PROPOSED`, `CONFIRMED`) with history kept.
- **Video call (mock).** `video_call_confirmations` holds each side's
  confirmation; when both have, `trip_members.video_verified_at` is set.
- **Emergency contact** lives in identity (`emergency_contacts`, `V14`): one
  per user, name, relationship and an AES-GCM phone under the user's key,
  shown back masked, and refused if it is the user's own verified number.
  It is required to propose or confirm a meet-up. `itinerary_shares` (§7)
  will reuse it rather than store a second copy.
- **Venues.** `verified_venues` gained plain coordinates (point generated),
  `city` and `time_zone`, seeded with 33 public places across the 15 cities
  the place picker offers. Proposals copy name and coordinates onto the
  meet-up; `venue_id` keeps the allowlist a foreign key.
- **Daylight** is computed, not assumed: sunrise and sunset at the venue's
  coordinates (NOAA equations), a start at least 1 h after sunrise and 2 h
  before sunset so the meet-up also ends in daylight, at least 3 h ahead,
  at most 30 days ahead, on a local day before the trip starts, on a
  15-minute grid. The venue list carries each venue's windows for 14 days.
- **Check-in** needs both signals at once (`ck_meetup_checkins_arrival`):
  a point within 100 m of the venue measured in PostGIS
  (`ck_meetup_checkins_geofence` holds the database to 100 m) and a selfie
  through the EXIF-stripping pipeline. A refused check-in stores nothing.
  Rows are created for both people at confirmation so the safety job can see
  who has not arrived. The other person sees only "arrived", never a point.
  Selfies are purged after 7 days.
- **Safety job.** Every minute, per confirmed meet-up under
  `FOR UPDATE SKIP LOCKED`: 30 min after the start, an email to anyone
  neither checked in nor "safe"; 60 min, their emergency contact is alerted
  (pluggable `EmergencyAlertSender`, now in identity and shared with live
  trips; the demo `mailbox` sender delivers the
  alert text to the user's own inbox with the contact's number masked);
  90 min, a meet-up not completed becomes `NO_SHOW` and each person who did
  not arrive is a trust input. The other person is never told.
- **Comfort notes** are optional and sealed under their author's key.

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

**As built (settlement).** What the settlement module added or changed
(`V17`-`V18`), with the reason for each departure from the schema above:

- **The lock is the meet-up's.** The ledger opens once the host has at
  least one companion `LOCKED` through the meet-up gate; there is no second
  lock and no deposit. Trips exposes `TripCrew` (no endpoint): the host plus
  every companion ever locked in, with whether they are still on the trip.
  Travellers can add expenses; a companion who stepped back later can still
  see the ledger and settle what is theirs; anyone else gets 404.
- **Money is whole paise in `BIGINT`, not `NUMERIC(12,2)`.** Neither is
  floating point, but integer paise leave no scale to get wrong in the
  application, splits are exact integer arithmetic, and the JSON carries
  integers. Requests are read as exact decimals so `1250.5` is refused
  rather than truncated. `currency` stays, CHECKed to `INR`.
- **Expenses are ledger transactions, not mutable rows.** `expenses` /
  `expense_shares` with a bulk-updated `settled_at` became a
  `ledger_transactions` header (`kind` `EXPENSE`, `EXPENSE_REVERSAL` or
  `SETTLEMENT`) with its legs in `ledger_entries`: the payer is DEBITED the
  amount, each sharer CREDITED their share; a net balance is
  `SUM(debit) - SUM(credit)` (positive = owed to them). A correction is a
  reversal (every leg turned round, `reverses_id` UNIQUE so once only); a
  confirmed payment debits the payer and credits the payee. "Settled" is
  read from the balances, so nothing is ever updated.
- **Double entry is held by the database.** A deferred constraint trigger
  refuses to commit a transaction with fewer than two legs, unequal debits
  and credits, or a total that differs from its header. A composite foreign
  key keeps every leg on its transaction's trip.
- **Append-only by trigger as well as by grant.** The app connects as the
  table owner, which a REVOKE cannot restrict, so `ledger_entries`,
  `ledger_transactions`, `settlement_state_transitions` and
  `settlement_overdue_notices` carry a BEFORE UPDATE/DELETE/TRUNCATE trigger
  that refuses every role. The REVOKE from PUBLIC is in place for when the
  app connects as a separate role (to be split at deploy).
- **Idempotency** lives on the transaction header,
  `UNIQUE (recorded_by_user_id, idempotency_key)` plus a SHA-256 request
  fingerprint: an expense's key is its client-chosen `clientExpenseId`, a
  reused ID with other details is `409 expense_id_reused`. A confirmed
  settlement's key is derived from its ID, and `settlement_id` is UNIQUE, so
  a settlement reaches the ledger once however many confirms race.
- **One write at a time per trip.** Every write locks the trip's
  `trip_ledgers` row first, then works the plan out again. `@Version` on
  `settlements` stays as the second guard (tested on its own).
- **Plan, held amounts and `VOIDED`.** After every write the planner runs the
  debt simplification on the ledger balances, with `MARKED_PAID` and
  `DISPUTED` settlements holding their amounts out (money may have moved,
  or someone says it did not). A `PROPOSED` settlement that still matches the
  new plan exactly is kept; any other is set to the new terminal state
  `VOIDED` (reason `PLAN_REPLACED`, no actor) and replaced.
  `settlement_state_transitions.from_status` is null for creation.
- **Disputes** may come from either person on an open settlement. The amount
  stays held out of the plan and a `SETTLEMENT_DISPUTED` outbox event asks a
  moderator to look; resolving one is a new settlement, never an edit.
- **UPI.** The payee's UPI ID lives in identity (`upi_ids`, `V17`), AES-GCM
  under their key, validated to `name@bank`. The `upi://pay` link is built
  for the payer at read time instead of stored in `upi_deep_link`, so a
  changed ID never leaves a stale link behind; an ID changed in the last 48
  hours is pointed out. No UPI ID means no link and "ask in chat".
- **Ghosting a settlement** is a trust input written to the outbox
  (`SETTLEMENT_OVERDUE`, role `PAYER` or `PAYEE`): a suggested payment still
  not marked as sent 7 days after the trip ended (and 7 days after it was
  suggested), or a payment marked as sent and not confirmed for 7 days. Once
  per trip, person and role (`settlement_overdue_notices`). Settlement never
  calls the trust score; a test pins its one trust method.

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

**As built (live trip and safety).** What the safety module added or changed
(`V19`), with the reason for each departure from the schema above:

- **One emergency contact, really.** `itinerary_shares` keeps no copy of the
  contact's name or phone; alerts read identity's `emergency_contacts`. It
  holds the sharing itself instead: the check-in interval (3, 6, 12 or 24 h),
  an optional note for the contact (AES-GCM under the traveller's key), and
  when sharing started and stopped. Sharing needs a locked trip and an
  emergency contact; stopping is free and cancels the waiting check-in.
- **One alert sender.** `EmergencyAlertSender` and its demo `mailbox`
  implementation moved from meet-up to `identity.emergency`, with a general
  `situation` sentence, so meet-ups and live trips reach the contact the same
  way and safety never imports meet-up. Selected by
  `tripmate.identity.emergency-alert-sender` (`EMERGENCY_ALERT_SENDER`,
  falling back to the older `MEETUP_ALERT_SENDER`).
- **Check-ins.** One PENDING per traveller per trip (partial unique index).
  Due one interval after sharing starts (or after the trip's first midnight,
  IST); "I'm safe" answers it and schedules the next. The safety job emails
  "are you safe?" when one comes due and, 60 minutes later, alerts the
  emergency contact with the trip, the other travellers' first names, the
  note and the last answered check-in's exact place, marks it `MISSED` and
  schedules the next, so the net stays up. Nothing is scheduled after noon
  on the day after the trip ends. Added `pinged_at`, `escalated_at` and a
  `CANCELLED` status.
- **Locations** are stored as two-decimal coordinates (about a kilometre,
  CHECKed) with the geography point generated from them, as for trips, plus
  the exact point sealed under the traveller's key and bound to its row.
  Only an emergency alert opens the exact point. Other travellers see a
  first name, whether they share, and when they last checked in: never a
  location.
- **SOS** (`client_sos_id` for retries, `alert_attempts`,
  `escalated_at`): recorded and flagged for moderation (`SAFETY_SOS_RAISED`
  outbox event) in one transaction, then alerted in another, so a failing SMS
  gateway delays the alert (the job retries up to 10 times) but never loses
  the SOS. Allowed to anyone on the trip, sharing or not, with or without an
  emergency contact. Other travellers are not told: the threat may be one of
  them. The traveller closes their own SOS with "I'm safe now".
- **Free by construction.** The safety module has no trust input at all; a
  test pins that it imports only identity, trips and the platform, names no
  trust input, and that its one outbox publisher is the moderation flag,
  taking only IDs.
- **Conflict pre-mortem** lives in trips (which already depends on vibe), not
  safety: `ConflictPremortem` with a rule-based default, comparing the caller
  with each other traveller only, in fixed sentences with one question each.

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

**As built (trust score and reviews).** Where V21 and the code settle or
depart from the spec above and the engine spec below:

- **No response-rate input — settled, not deferred.** Scoring how often
  someone replies would cost a person who stops answering someone they
  want to avoid, which is the safety carve-out's own argument ("the safe
  exit must always be the cheapest action"). `RESPONSE_RATE_SAMPLE` is not
  built and will not be: no reply-based signal (reply rate, reply time,
  read receipts) is a trust input. Its 5% went to reliability. Weights:
  verification 0.30, peer rating 0.30, completion 0.20, reliability 0.20.
  The `RESPONSE_RATE_SAMPLE` entry and `response_rate_component` in the
  engine spec below are kept only as the record of what was decided
  against.
- **Components are 0-100.** Verification is `(tier points + vouch bonus) /
  60 × 100`; peer rating maps the Bayesian 1-5 average to 0-100;
  completion is `(finished + 1) / (finished + 2)`, so a new account starts
  at 50; reliability is 100 minus fixed deductions. Each value is rounded
  before it is weighted, so the points shown add up to the score exactly.
- **Reliability instead of a ghost ratio.** "Ghosts / commitments" needs a
  denominator from meet-up data, and trust must not read meet-up tables
  (the comfort rating lives there). Instead each behaviour input already
  published takes off fixed points that fade with age: meet-up no-show 20,
  settlement ghosted as payer 15 or payee 8 (these three fade to half and
  stay), late change of plan 8, strongly flagged chat message 10 (these
  fade away). The "first two ghosts" nuance is dropped for one simple
  floor.
- **Vouch penalty:** 3 points per `VOUCH_PENALIZED`, fading, capped at 9,
  taken off after the weighted sum. `VouchService.penalizeVouchersOf` is
  called by `AccountModeration.suspend`.
- **`trust_score_events`** carries `detail`, `related_id` and
  `source_event_id` (UNIQUE, so a redelivered outbox event counts once)
  instead of a free JSONB payload, and no `processed_at`: the queue is
  `trust_score_state (user_id, dirty_since, computed_at)`. Every input
  marks its user dirty; the job recomputes from all inputs, so arrival
  order never matters. Scores older than a day are requeued (inputs
  fade), and any active verified account with no state row is queued too,
  which is how existing users got a score. Events and snapshots are
  append-only by trigger; a snapshot is written only when the score or
  breakdown changed.
- **The prior mean is a fixed 3.5**, not recomputed monthly.
- **Reviews:** only the host and companions still on the trip when it ended
  review each other (a companion who stepped back for safety neither
  reviews nor is reviewed, so stepping back cannot earn a retaliatory
  rating). Window: the day after the last day until 14 days later, IST.
  Hidden until both of a pair have written, or the window closes (a job
  reveals what was written then). The two writers of a pair are
  serialised by a transaction advisory lock. A trigger refuses edits and
  deletes; only `visible_at` moves, once, and a comment may be erased (set
  to null) for account deletion.
- **The score is shown only to its owner** (`GET /users/me/trust-score`),
  consistent with `user_public_facts`: other people meet it only as a
  `min_trust_score` filter. Deal-breakers are checked live, never cached,
  so a changed score takes effect on matching at once and the cached soft
  score stays correct.
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
touching that counterparty's public trust score. (Superseded: a safety
cancellation raises an ID-only moderation flag instead of a report — see
"As built" below.)

**As built (blocks and reports, V22).** Two tables instead of
`blocks_reports`: a block is a relationship that can be lifted, a report is
a case that must not be lost.

- **`user_blocks`** (identity, next to vouches), published to other modules
  as `user_block_facts` with both directions. A block hides two people from
  each other: trips browse, trip detail and join requests (as the 404 of an
  unknown trip) and chat (`conversation_closed`).
- **`reports`** (new `moderation` module): reason from a fixed list,
  details sealed under the reporter's key (`REPORT_DETAILS`), status
  OPEN → ACTIONED or DISMISSED once, never deleted (trigger). One open
  report per reporter about one person.
- **Who:** only someone the caller was matched with (host or accepted
  companion, whatever happened after) or travelled with, addressed by
  `matchId` or `tripId` + `userId`; anything else is a 404, so neither
  route probes accounts.
- **Auto-suspend:** 3 different reporters within 30 days (dismissed
  reports not counted), counted under the reported account's row lock.
  Suspension ends every session and penalises active vouchers, once.
- **A safety cancellation raises a moderation flag, not a report.** No
  automatic `REPORT` row: a report names the other person as reported and
  feeds the auto-suspend count, which is a judgement the person who stepped
  back never made. Instead, calling a meet-up off with `SAFETY` publishes
  `SAFETY_MEETUP_CANCELLED` (aggregate `SAFETY_REPORT`, the meet-up ID) in
  the same transaction as the cancellation, through
  `MeetupModerationEvents`, the same way an SOS publishes
  `SAFETY_SOS_RAISED`. Its payload is exactly `userId` (who cancelled),
  `meetupId`, `tripId` and `cancelledAt`: never the comfort rating, a note,
  a reason or anything about the rating. The publishing method takes three
  UUIDs and an `Instant` and nothing else (pinned by
  `MeetupModuleBoundaryTest`); an integration test checks the stored
  payload has exactly those four keys and no rating. Trust's handler does
  not accept the type, so the flag costs nobody anything. A match stopped
  by a low comfort rating raises **no** flag — a settled decision, not a
  gap: "who stepped back" after a completed meet-up would itself tell a
  moderator that person rated it below the threshold, and no flag, report
  or moderator view may carry anything derived from the comfort rating.
  Either person can still file a report themselves from the same screen.
  There is no moderator view of the safety flags yet (neither SOS nor
  safety cancellations); they sit in the outbox as the record, and the
  admin panel reads reports. A moderator view of both is planned (PLAN,
  CI/CD & hardening pass); it shows only what the flags carry (who, which
  SOS or meet-up, which trip, when) and never any comfort-rating detail.
**As built (compliance, V23).**

- **Audit log.** A BEFORE INSERT trigger fills `sequence_no` (gapless),
  `prev_hash` (64 zeros for the first row) and `row_hash =
  sha256(audit_row_text(...))` under one transaction-scoped advisory lock,
  so concurrent writers can't chain onto the same row and no caller can
  choose a hash. `audit_row_text` is a fixed `|`-joined form (UTC
  timestamp to the microsecond, metadata as jsonb prints it). Append-only
  by trigger. `ip_address` is not stored: the log outlives account
  deletion, so it holds IDs, codes and numbers only (`AuditLog` refuses
  anything else in metadata). Written in the same transaction as: admin
  verification decisions, suspensions and reinstatements (actor null for
  the report threshold), reports filed and closed, SOS raised, settlement
  confirmed, consent changes, data requests (requested, export completed
  and downloaded, account deleted), manual integrity checks. Logins stay in
  `login_history`.
- **Integrity check** (`AuditIntegrity`, daily and on demand from the
  admin API): reads every row in sequence order, recomputes SHA-256 in
  Java, checks the sequence and the prev-hash links, and records the result
  in `audit_integrity_checks` (append-only); a break is logged as an error.
- **`consent_records`** is owned by identity (it's account data), one row
  per grant or withdrawal, append-only, latest row wins. TOS and privacy
  policy recorded at sign-up with the published version
  (`tripmate.consent.*`); existing users backfilled as version 2026-09.
  Only LOCATION_SHARING and MARKETING can be withdrawn through the API.
- **`data_requests`** (new `compliance` module). `export_file_key` became
  `export_ciphertext`: the export JSON is sealed under the user's own key,
  kept 7 days, then cleared. Object storage only takes image keys and
  serves them through signed media links, which an export must never go
  through, and a later deletion's shred destroys a waiting export. Added
  `expires_at` and `attempts`; one pending request per type per user.
  Deletion needs the password again.
- **Export** is assembled from each module's `PersonalDataContributor`
  (account, vibe, trips, ledger, safety with rounded points only, trust
  with only visible received reviews, chat messages the user sent,
  decrypted), so no module reads another's tables. Never exported:
  password hash, TOTP secret, phone ciphertext or blind index, other
  people's messages, the meet-up comfort rating.
- **Deletion** = every `PersonalDataEraser` (vibe rows, review comments the
  user wrote) + identity's `AccountDeletion`: email → `deleted-<id>@deleted.invalid`,
  name → "Deleted user", phone, photo and tier cleared, status DELETED,
  `deleted_at` set, emergency contact and UPI rows deleted, vouches
  revoked, every session revoked, every data key shredded (chat, notes,
  locations, report details and exports become unreadable). The row stays
  because trips, ledgers and reviews point at it. The photo file is removed
  after commit.
- **PII masking in logs:** `PiiMaskingLogCustomizer` (registered as
  `logging.structured.json.customizer`) masks emails and 10-16 digit phone
  numbers in every string field of every log line; an integration test
  signs up, sends a phone code, fails a login and logs both on purpose, and
  checks neither appears in the captured output.
- **Admin routes** (all under /admin, so ADMIN + `otp` in amr, plus
  `AdminGuard`): report queue with details decrypted, report decision
  (ACTIONED/DISMISSED, optional suspend), suspend/reinstate, audit log page
  + latest check, run check now, data request list (status only). A
  moderator can't decide a report they're part of or act on their own
  account.

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
   messages received) in chat. **Decided against, not built** — see §8
   "As built (trust score and reviews)".
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
  + 0.05 * response_rate_component     -- decided against; as built, this 5% is in reliability

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
