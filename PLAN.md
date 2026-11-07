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
- [ ] docker-compose.yml: backend and frontend services
- [x] application.yml (+ -test, -docker profiles), `.env.example`
- [x] Flyway baseline migration (extensions: pgcrypto/citext/postgis)
- [x] Global exception handler, correlation-ID filter, structured JSON logging
- [x] Actuator health check, OpenAPI (springdoc) wired, `/api/v1` base path
- [x] Security headers filter (CSP/HSTS/etc.) + CORS allowlist config
- [x] Vite + React + TS scaffold, Tailwind, Zustand store shape, React Router skeleton,
      Axios instance with silent-refresh interceptor (no screens yet — plumbing only)

## Step 2a — Identity backend: signup, login, email verify
- [ ] users, user_encryption_keys, otp_codes tables (Flyway)
- [ ] Register (Argon2id, breached-password check, Bean Validation DTOs)
- [ ] Email verify via OTP (hashed, expiring, attempt-limited)
- [ ] Login: identical responses / no enumeration, dummy-hash timing defense
- [ ] Login rate limiting (Bucket4j, per-IP + per-account), lockout, CAPTCHA hook
- [ ] login_history table + new-device email alert

## Step 2b — Identity frontend: signup/login screens
- [ ] Signup, login, email-verify-pending screens; client + server validation
- [ ] Loading/error/empty states; generic error display (no raw API errors)
- [ ] Zustand auth store wired to real endpoints from 2a

## Step 3a — JWT, refresh rotation, sessions, 2FA backend
- [ ] Access token (15 min) + refresh token rotation with reuse detection
- [ ] refresh_token_families table, Redis-backed current-hash lookup
- [ ] httpOnly/Secure/SameSite refresh cookie + CSRF token on /auth/refresh
- [ ] Logout + "revoke all devices"
- [ ] active_sessions listing endpoint
- [ ] TOTP 2FA setup + backup codes, mandatory for ADMIN role
- [ ] Authorization test suite pattern established (owner/non-owner/anon) — applied retroactively to Steps 2a-3a endpoints

## Step 3b — Auth frontend: 2FA + session management
- [ ] 2FA challenge screen (TOTP + backup code), access token kept in memory only
- [ ] Axios interceptor: silent refresh + retry on 401
- [ ] Session/device management screen (list + revoke-all-devices)
- [ ] Route guards by auth state

## Step 3c — Slim deploy (backend + frontend + managed Postgres, auth only)
- [ ] Deploy backend + frontend to Render/Railway, managed Postgres (PostGIS enabled), managed Redis
- [ ] Only auth (Steps 1-3b) needs to work end-to-end at this point — a real URL to demo signup/login/2FA/sessions
- [ ] Redeploy script/workflow established here gets reused after every later step, not rebuilt at Step 13

## Step 4a — Verification tiers backend: phone OTP, selfie match, vouching
- [ ] Phone field: AES-GCM envelope encryption + blind index, phone OTP tier
- [ ] identity_verifications table (PHONE, SELFIE_MATCH only — no document fields, no document upload pipeline)
- [ ] Selfie upload pipeline (magic-byte check, size cap, randomized storage name, EXIF strip, signed URL
      retrieval), compared against profile_photo_key
- [ ] Mock `LivenessVerifier` interface (liveness signal + similarity score, auto-decide with admin fallback
      for flagged/ambiguous cases only) behind a pluggable interface
- [ ] Scheduled selfie-purge job (default 7-day retention) + test proving no image key survives past
      purge_due_at in a terminal state
- [ ] vouches table; vouch create/revoke endpoints (requires voucher at SELFIE_MATCH tier, capped active count)
- [ ] id_verification_tier + vouch bonus/penalty drive Trust Score input events

## Step 4b — Verification frontend: verification centre
- [ ] Tier progress bar (NONE/EMAIL/PHONE/SELFIE_MATCH — no document step), phone OTP entry, selfie
      capture screen (liveness prompt, not a static upload)
- [ ] Vouch-for-a-friend screen (visible only once SELFIE_MATCH is reached), active vouch list
- [ ] Route guards extended to require a minimum tier for gated screens
- [ ] Admin queue screen for flagged/ambiguous verifications and reports — not a general document review queue, since there are no documents

## Step 5a — Vibe Profile backend
- [ ] vibe_profiles, deal_breakers tables
- [ ] Quiz submission endpoint (12-15 Qs -> 8-dim profile), Bean Validation
- [ ] Profile edit/retrieve endpoints with ownership checks

## Step 5b — Vibe Profile frontend
- [ ] Multi-step quiz form with saved progress (resume mid-quiz)
- [ ] Deal-breakers section, profile edit screen

## Step 6a — Trips backend: create, browse, PostGIS, optimistic-lock join
- [ ] trips, trip_members tables (PostGIS geography column + GIST index)
- [ ] Create trip (host), browse trips (radius/date/budget filters)
- [ ] Join request -> accept/reject flow
- [ ] Optimistic-lock slot claim + concurrency test (N concurrent joins, 1 slot, exactly 1 wins)

## Step 6b — Trips frontend: browse, create, join inbox
- [ ] Trip browse screen with filters, trip create form
- [ ] Join request inbox (host side: accept/reject; requester side: status)

## Step 7a — Explainable matching backend
- [ ] Hard filters (deal_breakers) then weighted soft score over vibe dimensions
- [ ] match_score_cache table, invalidation on vibe_profile change
- [ ] Explainability payload: matches[] / mismatches[] with human-readable labels

## Step 7b — Matching frontend: trip detail breakdown
- [ ] Trip detail screen showing explainable match breakdown (% + matches/mismatches)

## Step 8a — Guided masked chat backend
- [ ] conversations, chat_messages, moderation_flags tables
- [ ] WebSocket/STOMP auth on CONNECT + per-SUBSCRIBE authorization
- [ ] Message sanitization (java-html-sanitizer) + PII-redaction regex before persist
- [ ] Rule-based icebreaker generator (AI adapter interface, rule-based default impl)
- [ ] Moderation flags (off-platform push / money request / harassment) -> trust score penalty hook
- [ ] Progressive disclosure: contact unlock endpoint gated on trip lock

## Step 8b — Chat frontend
- [ ] Masked chat UI (WebSocket client), icebreaker suggestion chips
- [ ] Contact-unlock banner once trip is locked

## Step 8c — Public meet-up backend
- [ ] verified_venues (admin-seeded allowlist), meetups, meetup_checkins, meetup_feedback tables
- [ ] trip_members.video_verified_at mock confirmation step, gates meetup proposal
- [ ] Propose/confirm meetup (venue must come from allowlist, daylight-hours validation)
- [ ] Geofenced + selfie check-in (ST_DWithin 100m + EXIF-stripped selfie upload)
- [ ] Private comfort-rating feedback (never exposed to counterparty or trust score)
- [ ] 30/60-minute missed-arrival ping scheduler -> emergency contact escalation
- [ ] Trip lock gate: trip_members.status -> LOCKED only once meetup COMPLETED and no below-threshold rating

## Step 8d — Public meet-up frontend
- [ ] Meetup proposal/scheduling screen (venue picker from allowlist, time picker constrained to daylight hours)
- [ ] Arrival check-in screen (geofence status + selfie capture)
- [ ] Private comfort-rating prompt post-meetup
- [ ] "This match didn't proceed" generic decline screen (no reason shown, either direction)

## Step 9a — Trip Lock + Settlement backend
- [ ] ledger_entries (append-only, no UPDATE/DELETE grants), settlements,
      settlement_state_transitions, idempotency key handling — no wallets, no custody
- [ ] Debt simplification algorithm (pure function, property-tested: conservation, correctness, ≤N-1 settlements)
- [ ] Settlement state machine service (PROPOSED/MARKED_PAID/CONFIRMED/DISPUTED) — only payee's CONFIRM writes the ledger
- [ ] UPI deep-link generation from payee's self-declared upi_vpa (nullable, graceful fallback to "ask in chat")
- [ ] Trip lock transition now driven by Step 8c's meetup gate, not escrow deposit
- [ ] Integrity tests: confirm-without-marked-paid rejected, self-confirm rejected, concurrent confirms produce exactly one ledger pair

## Step 9b — Settlement frontend
- [ ] Trip lock confirmation screen (reflects meetup-gated lock from 8d, no deposit step)
- [ ] Settle-up screen: debt-simplified settlement list, "mark paid" / "confirm" actions, UPI deep-link button
- [ ] Settlement/ledger history screen (obligations settled, not a wallet balance)

## Step 10a — Live Trip Mode & safety backend
- [ ] itinerary_shares, checkins, sos_events tables
- [ ] Scheduled "are you safe?" check-in job + missed-checkin escalation
- [ ] SOS endpoint (last known location + buddy details + itinerary -> emergency contact)
- [ ] AI Conflict Pre-mortem (rule-based default: flag large vibe-dimension deltas pre-trip)

## Step 10b — Safety frontend
- [ ] Active trip dashboard: check-in prompts, SOS button, geofenced check-in UI
- [ ] Conflict pre-mortem display pre-trip

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
