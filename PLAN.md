# TripMates — PLAN.md

Ordered checklist of every remaining step. One step = one reply, per
`CLAUDE.md`. Every backend step is immediately followed by the frontend
step that makes it demoable — frontend is not a Step 13 afterthought.
Tick a box when a step is verified working, not just written. Once Step 3c
(slim deploy) lands, redeploy after every later step is verified — don't
let the live instance drift out of date with main.
Deadline: 2026-11-30.

## Step 0 — Foundations (done)
- [x] DESIGN.md — full schema, module boundaries, Trust Score + Settlement + Public Meet-up state machine specs
- [x] THREAT_MODEL.md — STRIDE-lite + physical-safety threats for the meet-up protocol
- [x] PLAN.md — this file
- [x] CLAUDE.md — working protocol, git rules, commit style, frontend scope
- [x] `.claude/settings.json` — `includeCoAuthoredBy: false`
- [x] Confirm baseline `mvn compile` works on the Spring Initializr skeleton (Spring Boot 4.1.2, Java 21)
- [x] Base package renamed `com.Tripmate.tripmate` → `com.tripmate`
- [x] Git repo initialized, pushed to `origin/main` (https://github.com/Pranjalchaudhary65/tripmates)
- [x] Major pre-code design pivot applied: platform never holds money (escrow → settlement/IOU model), public meet-up protocol added, 18+/email-uniqueness/match-cache-index corrections
- [x] `README.md` created with the Business Model section (freemium, no custody of funds) — full architecture/OWASP README content still lands in Step 13
- [x] Government ID verification removed entirely: selfie-only identity tiers, `LivenessVerifier` interface, selfie retention/purge window, vouching mechanism added
- [x] `DECISIONS.md` created, Step 0 entries appended

## Step 1 — Project skeleton & infra (backend + frontend scaffold)
- [x] pom.xml: Spring Data JPA, Spring Security, Validation, PostgreSQL driver,
      Flyway, Redis (Lettuce), WebSocket, Spring Mail, Bucket4j, MapStruct,
      Argon2 (Spring Security crypto), Testcontainers (Postgres+Redis), Spring Security Test
- [x] docker-compose.yml: Postgres+PostGIS, Redis
- [x] application.yml (+ -test, -docker profiles), `.env.example`
- [x] Flyway baseline migration (extensions: pgcrypto/citext/postgis)
- [x] Global exception handler, correlation-ID filter, structured JSON logging
- [x] Actuator health check, OpenAPI (springdoc) wired, `/api/v1` base path
- [x] Security headers filter (CSP/HSTS/etc.) + CORS allowlist config
- [x] Vite + React + TS scaffold, Tailwind, Zustand store shape, React Router skeleton,
      Axios instance with silent-refresh interceptor (no screens yet — plumbing only)

## Step 2a — Identity backend: signup, login, email verify
- [x] users, user_encryption_keys, otp_codes tables (Flyway)
- [x] Register (Argon2id, breached-password check, Bean Validation DTOs)
- [x] Email verify via OTP (hashed, expiring, attempt-limited)
- [x] Login: identical responses / no enumeration, dummy-hash timing defense
- [x] Login rate limiting (Bucket4j, per-IP + per-account), lockout, CAPTCHA hook
- [x] login_history table + new-device email alert

## Step 2b — Identity frontend: signup/login screens
- [x] Signup, login, email-verify-pending screens; client + server validation
- [x] Loading/error/empty states; generic error display (no raw API errors)
- [x] Zustand auth store wired to real endpoints from 2a

## Step 3a — JWT, refresh rotation, sessions, 2FA backend
- [x] Access token (15 min) + refresh token rotation with reuse detection
- [x] refresh_token_families table, Redis-backed current-hash lookup
- [x] httpOnly/Secure/SameSite refresh cookie + CSRF token on /auth/refresh
- [x] Logout + "revoke all devices"
- [x] active_sessions listing endpoint
- [x] TOTP 2FA setup + backup codes, mandatory for ADMIN role
- [x] Authorization test suite pattern established (owner/non-owner/anon) — applied retroactively to Steps 2a-3a endpoints

## Step 3b — Auth frontend: 2FA + session management
- [x] 2FA challenge screen (TOTP + backup code), access token kept in memory only
- [x] Axios interceptor: silent refresh + retry on 401
- [x] Session/device management screen (list + revoke-all-devices)
- [x] Route guards by auth state

## Step 3c — Slim deploy (backend + frontend + managed Postgres, auth only)
- [ ] Backend and frontend Dockerfiles; docker-compose.yml backend and frontend services built from them
- [ ] Deploy backend + frontend to Render/Railway, managed Postgres (PostGIS enabled), managed Redis
- [ ] Only auth (Steps 1-3b) needs to work end-to-end at this point — a real URL to demo signup/login/2FA/sessions
- [ ] Redeploy script/workflow established here gets reused after every later step, not rebuilt at Step 13

## Step 4a — Verification tiers backend: phone OTP, selfie match, vouching
- [x] Phone field: AES-GCM envelope encryption + blind index, phone OTP tier
- [x] identity_verifications table (PHONE, SELFIE_MATCH only — no document fields, no document upload pipeline)
- [x] Selfie upload pipeline (magic-byte check, size cap, randomized storage name, EXIF strip, signed URL
      retrieval), compared against profile_photo_key
- [x] Mock `LivenessVerifier` interface (liveness signal + similarity score, auto-decide with admin fallback
      for flagged/ambiguous cases only) behind a pluggable interface
- [x] Scheduled selfie-purge job (default 7-day retention) + test proving no image key survives past
      purge_due_at in a terminal state
- [x] vouches table; vouch create/revoke endpoints (requires voucher at SELFIE_MATCH tier, capped active count)
- [x] id_verification_tier + vouch bonus/penalty drive Trust Score input events

## Step 4b — Verification frontend: verification centre
- [x] Tier progress bar (NONE/EMAIL/PHONE/SELFIE_MATCH — no document step), phone OTP entry, selfie
      capture screen (liveness prompt, not a static upload)
- [x] Vouch-for-a-friend screen (visible only once SELFIE_MATCH is reached), active vouch list
- [x] Route guards extended to require a minimum tier for gated screens
- [x] Admin queue screen for flagged/ambiguous verifications and reports — not a general document review queue, since there are no documents
      (reports are not built yet; they join this screen with the reporting backend)

## Step 5a — Vibe Profile backend
- [x] vibe_profiles, deal_breakers tables
- [x] Quiz submission endpoint (12-15 Qs -> 8-dim profile), Bean Validation
- [x] Profile edit/retrieve endpoints with ownership checks

## Step 5b — Vibe Profile frontend
- [x] Multi-step quiz form with saved progress (resume mid-quiz)
- [x] Deal-breakers section, profile edit screen

## Step 6a — Trips backend: create, browse, PostGIS, optimistic-lock join
- [x] trips, trip_members tables (PostGIS geography column + GIST index)
- [x] Create trip (host), browse trips (radius/date/budget filters)
- [x] Join request -> accept/reject flow
- [x] Optimistic-lock slot claim + concurrency test (N concurrent joins, 1 slot, exactly 1 wins)

## Step 6b — Trips frontend: browse, create, join inbox
- [x] Trip browse screen with filters, trip create form
- [x] Join request inbox (host side: accept/reject; requester side: status)

## Step 7a — Explainable matching backend
- [x] Hard filters (deal_breakers) then weighted soft score over vibe dimensions
- [x] match_score_cache table, invalidation on vibe_profile change
- [x] Explainability payload: matches[] / mismatches[] with human-readable labels

## Step 7b — Matching frontend: trip detail breakdown
- [x] Trip detail screen showing explainable match breakdown (% + matches/mismatches)

## Step 8a — Guided masked chat backend
- [x] conversations, chat_messages, moderation_flags tables
- [x] WebSocket/STOMP auth on CONNECT + per-SUBSCRIBE authorization
- [x] Message sanitization (java-html-sanitizer) + PII-redaction regex before persist
- [x] Rule-based icebreaker generator (AI adapter interface, rule-based default impl)
- [x] Moderation flags (off-platform push / money request / harassment) -> trust score penalty hook
- [x] Progressive disclosure: contact unlock endpoint gated on trip lock

## Step 8b — Chat frontend
- [x] Masked chat UI (WebSocket client), icebreaker suggestion chips
- [x] Contact-unlock banner once trip is locked

## Step 8c — Public meet-up backend
- [x] verified_venues (admin-seeded allowlist), meetups, meetup_checkins, meetup_feedback tables
- [x] trip_members.video_verified_at mock confirmation step, gates meetup proposal
- [x] Propose/confirm meetup (venue must come from allowlist, daylight-hours validation)
- [x] Geofenced + selfie check-in (ST_DWithin 100m + EXIF-stripped selfie upload)
- [x] Private comfort-rating feedback (never exposed to counterparty or trust score)
- [x] 30/60-minute missed-arrival ping scheduler -> emergency contact escalation
- [x] Trip lock gate: trip_members.status -> LOCKED only once meetup COMPLETED and no below-threshold rating

## Step 8d — Public meet-up frontend
- [x] Meetup proposal/scheduling screen (venue picker from allowlist, time picker constrained to daylight hours)
- [x] Arrival check-in screen (geofence status + selfie capture)
- [x] Private comfort-rating prompt post-meetup
- [x] "This match didn't proceed" generic decline screen (no reason shown, either direction)

## Step 9a — Trip Lock + Settlement backend
- [x] ledger_entries (append-only, no UPDATE/DELETE grants), settlements,
      settlement_state_transitions, idempotency key handling — no wallets, no custody
- [x] Debt simplification algorithm (pure function, property-tested: conservation, correctness, ≤N-1 settlements)
- [x] Settlement state machine service (PROPOSED/MARKED_PAID/CONFIRMED/DISPUTED) — only payee's CONFIRM writes the ledger
- [x] UPI deep-link generation from payee's self-declared upi_vpa (nullable, graceful fallback to "ask in chat")
- [x] Trip lock transition now driven by Step 8c's meetup gate, not escrow deposit
- [x] Integrity tests: confirm-without-marked-paid rejected, self-confirm rejected, concurrent confirms produce exactly one ledger pair

## Step 9b — Settlement frontend
- [x] Trip lock confirmation screen (reflects meetup-gated lock from 8d, no deposit step)
- [x] Settle-up screen: debt-simplified settlement list, "mark paid" / "confirm" actions, UPI deep-link button
- [x] Settlement/ledger history screen (obligations settled, not a wallet balance)

## Step 10a — Live Trip Mode & safety backend
- [x] itinerary_shares, checkins, sos_events tables
- [x] Scheduled "are you safe?" check-in job + missed-checkin escalation
- [x] SOS endpoint (last known location + buddy details + itinerary -> emergency contact)
- [x] AI Conflict Pre-mortem (rule-based default: flag large vibe-dimension deltas pre-trip)

## Step 10b — Safety frontend
- [x] Active trip dashboard: check-in prompts, SOS button, geofenced check-in UI
- [x] Conflict pre-mortem display pre-trip

## Step 11a — Double-blind reviews + Trust Score Engine backend
- [ ] reviews table (double-blind visibility logic)
- [ ] outbox_events, trust_score_events, trust_score_snapshots tables
- [ ] Trust Score scheduler (Bayesian peer rating, time decay, verification/completion/ghost/response components)
- [ ] Block/report + auto-suspend thresholds

## Step 11b — Reviews/Trust frontend
- [ ] Review submission screen (double-blind, no peeking until both submit)
- [ ] Trust score profile page (component breakdown)
- [ ] Report & block UI

## Step 12a — Compliance & audit backend
- [ ] audit_log hash chain + integrity-check scheduler
- [ ] consent_records, data_requests (export + soft-delete/crypto-shred deletion)
- [ ] PII masking in logs verified end-to-end

## Step 12b — Admin moderation panel frontend
- [ ] Admin moderation panel: verification queue, reports, audit log view, data requests

## Step 13 — CI/CD & hardening pass
- [ ] GitHub Actions: build, test (Testcontainers), gitleaks, OWASP dependency-check, Trivy on image
- [ ] README: architecture diagram, Design Decisions & Trade-offs, OWASP Top 10 checklist
- [ ] Full authorization-test audit across every endpoint
- [ ] Deploy to Render/Railway

Any step above that turns out too large for one reply gets split further
(e.g. 9a into 9a-i ledger, 9a-ii state machine, 9a-iii concurrency test) —
split, don't cram.
