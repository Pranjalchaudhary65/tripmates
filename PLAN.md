# TripMates — PLAN.md

Ordered checklist of every remaining step. One step = one reply, per
`CLAUDE.md`. Tick a box when a step is verified working, not just written.
Deadline: 2026-11-30.

## Step 0 — Foundations (this step)
- [x] DESIGN.md — full schema, module boundaries, Trust Score + Escrow state machine specs
- [x] THREAT_MODEL.md — STRIDE-lite
- [x] PLAN.md — this file
- [x] CLAUDE.md — working protocol
- [ ] Confirm baseline `mvn compile` works on the Spring Initializr skeleton (done — Spring Boot 4.1.2, Java 21)

## Step 1 — Project skeleton & infra
- [ ] pom.xml: add Spring Data JPA, Spring Security, Validation, PostgreSQL driver,
      Flyway, Redis (Lettuce), WebSocket, Spring Mail, Bucket4j, MapStruct,
      Argon2 (Spring Security crypto), Testcontainers (Postgres+Redis), Spring Security Test
- [ ] docker-compose.yml: Postgres+PostGIS, Redis, app
- [ ] application.yml (+ -test, -docker profiles), `.env.example`, `.gitignore` check
- [ ] Flyway baseline migration (extensions: pgcrypto/citext/postgis)
- [ ] Global exception handler, correlation-ID filter, structured JSON logging
- [ ] Actuator health check, OpenAPI (springdoc) wired
- [ ] Security headers filter (CSP/HSTS/etc.) + CORS allowlist config
- [ ] `git init` + first commit

## Step 2 — Identity: signup, login, email verify
- [ ] users, user_encryption_keys, otp_codes tables (Flyway)
- [ ] Register (Argon2id, breached-password check, Bean Validation DTOs)
- [ ] Email verify via OTP (hashed, expiring, attempt-limited)
- [ ] Login: identical responses / no enumeration, dummy-hash timing defense
- [ ] Login rate limiting (Bucket4j, per-IP + per-account), lockout, CAPTCHA hook
- [ ] login_history table + new-device email alert

## Step 3 — JWT, refresh rotation, sessions, 2FA
- [ ] Access token (15 min) + refresh token rotation with reuse detection
- [ ] refresh_token_families table, Redis-backed current-hash lookup
- [ ] httpOnly/Secure/SameSite refresh cookie + CSRF token on /auth/refresh
- [ ] Logout + "revoke all devices"
- [ ] active_sessions listing endpoint
- [ ] TOTP 2FA setup + backup codes, mandatory for ADMIN role
- [ ] Authorization test suite pattern established (owner/non-owner/anon) — applied retroactively to Steps 2-3 endpoints

## Step 4 — Phone OTP + ID document + selfie match (verification tiers)
- [ ] Phone field: AES-GCM envelope encryption + blind index, phone OTP tier
- [ ] identity_verifications table; file upload pipeline (magic-byte check, size cap,
      randomized storage name, EXIF strip, signed URL retrieval)
- [ ] Mock IdentityVerifier interface (admin approve/reject) behind pluggable interface
- [ ] Selfie-match tier (mock comparator behind interface)
- [ ] id_verification_tier drives first Trust Score input event

## Step 5 — Vibe Profile
- [ ] vibe_profiles, deal_breakers tables
- [ ] Quiz submission endpoint (12-15 Qs -> 8-dim profile), Bean Validation
- [ ] Profile edit/retrieve endpoints with ownership checks

## Step 6 — Trips: create, browse, PostGIS, optimistic-lock join
- [ ] trips, trip_members tables (PostGIS geography column + GIST index)
- [ ] Create trip (host), browse trips (radius/date/budget filters)
- [ ] Join request -> accept/reject flow
- [ ] Optimistic-lock slot claim + concurrency test (N concurrent joins, 1 slot, exactly 1 wins)

## Step 7 — Explainable matching
- [ ] Hard filters (deal_breakers) then weighted soft score over vibe dimensions
- [ ] match_score_cache table, invalidation on vibe_profile change
- [ ] Explainability payload: matches[] / mismatches[] with human-readable labels

## Step 8 — Guided masked chat
- [ ] conversations, chat_messages, moderation_flags tables
- [ ] WebSocket/STOMP auth on CONNECT + per-SUBSCRIBE authorization
- [ ] Message sanitization (java-html-sanitizer) + PII-redaction regex before persist
- [ ] Rule-based icebreaker generator (AI adapter interface, rule-based default impl)
- [ ] Moderation flags (off-platform push / money request / harassment) -> trust score penalty hook
- [ ] Progressive disclosure: contact unlock endpoint gated on trip lock

## Step 9 — Trip Lock + Commitment Escrow
- [ ] wallets, ledger_entries (append-only, no UPDATE/DELETE grants), escrows,
      escrow_state_transitions, idempotency key handling
- [ ] Video-verification confirmation step (mock) gating lock
- [ ] Escrow state machine service (PENDING/LOCKED/RELEASED/FORFEITED/REFUNDED)
- [ ] Concurrency test proving double-spend/corruption is impossible
- [ ] Expense-split ledger (Splitwise-lite) tied into the same ledger

## Step 10 — Live Trip Mode & safety
- [ ] itinerary_shares, checkins, sos_events tables
- [ ] Scheduled "are you safe?" check-in job + missed-checkin escalation
- [ ] SOS endpoint (last known location + buddy details + itinerary -> emergency contact)
- [ ] AI Conflict Pre-mortem (rule-based default: flag large vibe-dimension deltas pre-trip)

## Step 11 — Double-blind reviews + Trust Score Engine
- [ ] reviews table (double-blind visibility logic)
- [ ] outbox_events, trust_score_events, trust_score_snapshots tables
- [ ] Trust Score scheduler (Bayesian peer rating, time decay, verification/completion/ghost/response components)
- [ ] Block/report + auto-suspend thresholds

## Step 12 — Compliance & audit
- [ ] audit_log hash chain + integrity-check scheduler
- [ ] consent_records, data_requests (export + soft-delete/crypto-shred deletion)
- [ ] PII masking in logs verified end-to-end

## Step 13 — Frontend (React + Vite + TS)
- [ ] Auth flows, Vibe quiz, Trip browse/create/join, masked chat UI, escrow/lock UI,
      live-trip/SOS UI, reviews UI — one step per major flow, matching backend step order

## Step 14 — CI/CD & hardening pass
- [ ] GitHub Actions: build, test (Testcontainers), gitleaks, OWASP dependency-check, Trivy on image
- [ ] README: architecture diagram, Design Decisions & Trade-offs, OWASP Top 10 checklist
- [ ] Full authorization-test audit across every endpoint
- [ ] Deploy to Render/Railway

Steps 13's sub-items will be split into their own one-step replies as we
reach them; same for any step above that turns out too large for one
reply — split, don't cram.
