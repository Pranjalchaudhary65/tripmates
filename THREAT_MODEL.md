# TripMates — THREAT_MODEL.md

STRIDE-lite analysis. For each category: the specific threats that matter
for a "trust strangers to travel with" app (not a generic checklist), the
mitigation, and where it lives in code/schema. This file is read alongside
`DESIGN.md` and the README's OWASP Top 10 checklist — this file asks "what
can go wrong", the README checklist confirms "here's proof we covered it".

Trust boundaries in this system:
- Browser ↔ API (untrusted input, the biggest boundary)
- API ↔ Postgres (assume SQL only via parameterized JPA/JPQL, never trusted beyond that)
- API ↔ Redis (session/refresh-token state, assume network between them could be sniffed inside the Docker network — still TLS in prod)
- API ↔ Object storage (verification selfies, profile/meetup photos — never served directly to the browser)
- API ↔ pluggable AI adapter (treated as untrusted output generator — never executes anything, never gets secrets, output is always sanitized before storage/display)
- Mobile/web client ↔ WebSocket (same auth boundary as REST, often forgotten)

---

## S — Spoofing

| Threat | Mitigation | Where |
|---|---|---|
| Attacker brute-forces/guesses passwords | Argon2id hashing (tuned cost), breached-password check via k-anonymity range query against HaveIBeenPwned-style API, per-IP + per-account rate limiting with exponential backoff, CAPTCHA after N failures | `identity.AuthService`, `Bucket4jConfig`, `ratelimit` interceptor |
| Stolen/replayed refresh token used to impersonate a user indefinitely | Refresh token **rotation with reuse detection** — a token used twice revokes the entire token family, forcing re-login on every device in that family | `identity.RefreshTokenService`, Redis `refresh:{familyId}` key, `refresh_token_families` table |
| Attacker fakes a trusted identity using someone else's photos | Tiered verification is explicitly a *confidence score*, not a boolean "verified" — selfie match tier compares the submitted selfie against `users.profile_photo_key` (mock `LivenessVerifier` in dev, pluggable commercial liveness/face-match provider in prod); trust score formula weights SELFIE_MATCH far higher than EMAIL-only, so the UI and matching never treat all "verified" users as equivalent. No government ID is involved at any tier — see `DESIGN.md` → "No government ID verification" for why, and note that this is a deliberately weaker assurance than a document check, compensated by the Public Meet-up Protocol | `identity.verification.LivenessVerifier`, `DESIGN.md` Trust Score spec |
| Face-spoofing — a photo-of-a-photo, a video replay, or a deepfake is presented to pass selfie verification | The `LivenessVerifier` interface requires a liveness signal (e.g. an in-app challenge like a prompted head-turn or blink, or a short live capture), not just a static image match — a still photo alone cannot satisfy it. Mock implementation simulates the liveness check in dev; the commercial provider swapped in later is where this actually gets hard to fool | `identity.verification.LivenessVerifier` |
| CSRF — a malicious page submits a cookie-authenticated request on behalf of a logged-in user | Access token is a bearer token the SPA attaches explicitly (not auto-sent); only the refresh token lives in a cookie, and that cookie is `httpOnly + Secure + SameSite=Strict` plus a double-submit CSRF token required on the one endpoint that reads it (`/auth/refresh`) | `SecurityConfig`, `CsrfTokenFilter` |
| WebSocket connection impersonates another user | JWT access token required and validated on STOMP `CONNECT`; every `SUBSCRIBE` is authorized against the principal's own conversation/trip membership before the subscription is accepted, not just at connect time | `chat.StompAuthChannelInterceptor` |
| User misrepresents their age at signup to access a platform whose purpose is introducing them to adult strangers | `date_of_birth` has a DB-level `CHECK` (≥18 years ago) that a direct API call can't bypass, not just a signup-form validation; service layer duplicates it for a clear rejection message | `DESIGN.md` §1 `users`, `identity.RegistrationService` |

## T — Tampering

| Threat | Mitigation | Where |
|---|---|---|
| Client sends a manipulated settlement/expense `amount` | Server never trusts a client-supplied amount for anything that affects the ledger — amounts are either server-computed (the debt-simplification algorithm's output) or validated against a server-side expected value before any `ledger_entries` insert | `settlement.SettlementService`, `settlement.ExpenseService` |
| Two concurrent requests overfill a trip slot, or double-write a settlement confirmation | Optimistic locking (`@Version`) on `trips` and `settlements`; append-only ledger means there is no mutable record to corrupt in the first place — a user's settled history is always `SUM(ledger_entries)`, never a column that two writers can race on | `DESIGN.md` §Trips, §Settlement |
| Attacker edits historical audit/ledger/transition rows directly (DB access, insider, bug) | Append-only tables have no `UPDATE`/`DELETE` grant for the app's DB role; `audit_log` is additionally hash-chained so even a privileged edit breaks `row_hash` continuity, caught by a scheduled integrity sweep | `DESIGN.md` §Append-only enforcement, flyway migration granting privileges |
| A user games the safety carve-out — marks ordinary (`CONVENIENCE`) cancellations as `SAFETY` to dodge the `GHOST` trust-score penalty | No individual `SAFETY` cancellation is ever penalized (that would defeat the point of the carve-out), but every one auto-files a `blocks_reports` row against the *counterparty*; a pattern of many `SAFETY` cancellations from the same user, each naming a different counterparty, surfaces through the same report-volume signal the moderation queue already uses for harassment — reviewed by a moderator, never auto-penalized on the canceller's trust score speculatively | `trust.TrustScoreService`, `platform.ModerationService`, `DESIGN.md` §Trust Score Engine → Safety carve-out |
| Mass-assignment — client sends extra JSON fields (`role: "ADMIN"`, `trustScore: 100`) that get bound onto an entity | Every endpoint binds to a dedicated request DTO with Bean Validation, never a JPA entity directly; MapStruct maps DTO → entity explicitly field-by-field, so an unmapped field is simply dropped, not silently persisted | All `*.dto.*Request` classes, MapStruct mappers |
| Tampered JWT (algorithm confusion, modified claims) | Signing key fixed server-side (HMAC-SHA256 or RSA, algorithm pinned — `alg: none` and mismatched-algorithm tokens rejected explicitly), short 15-minute expiry limits the damage window of a key compromise | `identity.JwtService` |

## R — Repudiation

| Threat | Mitigation | Where |
|---|---|---|
| User denies marking a settlement paid/confirmed, or disputes it after the fact / admin denies suspending an account | `audit_log` hash chain records every auth event, safety event, settlement confirmation, and admin action with `actor_user_id`, timestamp, and IP, in the same transaction as the change — not best-effort logging after the fact | `platform.AuditLogService`, `DESIGN.md` §9 |
| User claims "I never saw that review" / "I didn't ghost, that was a safety cancellation" | `settlement_state_transitions` and `trust_score_snapshots` keep a full timestamped history independent of current row state; `trip_members.cancellation_type`/`cancellation_reason` are recorded at cancellation time, not reconstructed later, so any score or settlement dispute can be reconstructed exactly as it happened | `DESIGN.md` §Settlement flow & state machine, §Trust Score spec |
| "The AI said something inappropriate, not me" (icebreaker/moderation messages) | AI/system-generated chat messages are stored with `sender_id = NULL` and a distinguishable type, never attributed to a real user, and are themselves subject to the same sanitization pipeline before storage | `chat.ChatMessage` entity |

## I — Information Disclosure

| Threat | Mitigation | Where |
|---|---|---|
| IDOR — user requests `/api/trips/{id}/settlements` or `/api/users/{id}/reviews` for an ID that isn't theirs | Deny-by-default `@PreAuthorize` plus an explicit ownership check inside the service (never trust the URL path alone); **every** endpoint has an authorization test asserting owner-allowed / non-owner-denied / anonymous-denied | `*.controller.*`, `*AuthorizationTest` suite |
| Phone number, emergency contact, precise location leak via a DB dump, log file, or API response | Field-level AES-GCM envelope encryption at rest for these fields; blind-index (HMAC) column for the one field (`phone`) that must stay searchable; PII masked before any log statement, correlation IDs used instead of raw identifiers in logs | `DESIGN.md` §1 `users`, §7 `itinerary_shares`/`checkins`, `platform.logging.PiiMaskingConverter` |
| A photo upload leaks a user's home address via embedded EXIF GPS metadata | Every uploaded image is re-encoded through a library that strips all EXIF/metadata before storage; original bytes are never persisted | `identity.upload.ImageSanitizer` |
| Verification selfie image served from a predictable/public URL, or retained longer than its purpose requires | Files are stored outside the web root under randomized (UUID) names; the only access path is a short-lived signed URL issued per-request to an authorized viewer (the user themselves or an admin reviewer). On top of that, a hard retention window (default 7 days) applies regardless of outcome — a scheduled job purges `selfie_image_key` once `purge_due_at` passes, and a test asserts no image key survives past that window in a terminal state | `identity.upload.SignedUrlService`, `identity.verification.SelfiePurgeScheduler`, `DESIGN.md` §1 `identity_verifications` |
| User enumeration via login/register/forgot-password responses or timing | Identical response body and (approximately) constant response time whether or not the email exists, for register/login/forgot-password; actual password check always runs (against a dummy hash) even when the email doesn't exist, so timing doesn't leak existence | `identity.AuthService` |
| Masked chat accidentally reveals real contact details before trip lock | PII-redaction regex runs server-side on every inbound message *before* persistence (not a display-time filter that could be bypassed by calling the API directly); contact unlock is a separate gated endpoint, not a flag on the message itself | `DESIGN.md` §4 `chat_messages` |
| Stack traces / internal exception details returned to the client | Global `@ControllerAdvice` maps every exception to a generic, non-leaking error body; nothing but a correlation ID is returned for 5xx | `platform.GlobalExceptionHandler` |

## D — Denial of Service

| Threat | Mitigation | Where |
|---|---|---|
| Credential stuffing / brute force exhausts login endpoint | Per-IP and per-account rate limiting (Bucket4j + Redis), exponential backoff lockout, CAPTCHA after N failures | `ratelimit.LoginRateLimiter` |
| Attacker floods WebSocket with messages | Per-connection message rate limit and max message size enforced at the STOMP interceptor, independent of the global HTTP rate limiter | `chat.StompAuthChannelInterceptor` |
| Resend-OTP / email endpoints used to spam a victim's inbox/phone | Resend throttle enforced via the `otp_codes` partial index (one live OTP per purpose at a time) plus a minimum resend interval | `identity.OtpService` |
| Large file upload exhausts storage/memory | Hard size cap enforced before the body is fully read (`Content-Length` pre-check + streaming size guard), magic-byte validation happens on a bounded buffer, not the whole file in memory | `identity.upload.FileValidator` |
| Expensive matching query (PostGIS + weighted scoring) called repeatedly to burn CPU | Global rate limiting applies to all endpoints, not just auth; `match_score_cache` avoids recomputation on repeated browse requests | `DESIGN.md` §3 `match_score_cache` |

## E — Elevation of Privilege

| Threat | Mitigation | Where |
|---|---|---|
| Regular user calls an admin-only endpoint (approve ID verification, suspend account, view audit log) | Deny-by-default method security; admin endpoints require `role = ADMIN` **and** mandatory 2FA on the admin account itself (an admin account without TOTP enabled cannot be used to call admin endpoints, enforced server-side, not just at login) | `SecurityConfig` (`@EnableMethodSecurity`), `AdminGuard` |
| Privilege escalation via mass assignment (`role` field in a profile-update request) | Same mass-assignment mitigation as under Tampering — DTOs never expose `role`/`trustScore`/`accountStatus` as writable fields on user-facing endpoints | DTO layer |
| A moderation/report feature is abused to get an innocent user auto-suspended (report-bombing) | Auto-suspend thresholds require reports from distinct reporters above a minimum trust score themselves, within a bounded window, and always create a reviewable admin case rather than an instant permanent ban | `platform.ModerationService`, `blocks_reports` table |
| Session fixation / stale session reuse after password change or "revoke all devices" | Changing a password or using "revoke all devices" revokes every `refresh_token_families` row for the user and clears the corresponding Redis keys in the same transaction — logout is not just a client-side token discard | `identity.SessionService` |

## Physical safety — Public Meet-up Protocol (beyond STRIDE)

STRIDE is built for information systems; a safety app that deliberately
puts two strangers in the same physical place needs a category STRIDE
doesn't have. These threats are specific to the meet-up rung of the trust
ladder (masked chat → video verification → **public meet-up** → trip
lock).

| Threat | Mitigation | Where |
|---|---|---|
| Venue spoofing — a bad actor proposes a private or isolated location dressed up as a public venue | A meet-up can only be proposed against a `venue_id` from the admin-curated `verified_venues` allowlist, enforced by a foreign key, not a free-text field the client controls | `DESIGN.md` §5 `verified_venues`, `meetups.venue_id` |
| Faked geofence check-in — spoofed GPS coordinates claim arrival at the venue without the person actually being there | Two independent signals required, not one spoofable one: `ST_DWithin(venue_point, arrival_point, 100)` **and** a timestamped, EXIF-stripped selfie captured in-app at check-in time; the missed-checkin escalation triggers if either signal is missing | `meetup.MeetupCheckinService`, `DESIGN.md` §5 `meetup_checkins` |
| Location leakage during meet-up scheduling or check-in | `venue_point` is a public, already-known location — there's nothing sensitive to leak there; a party's own `arrival_point` is used only server-side for the geofence check and is never returned to the counterparty, who only ever sees a boolean "arrived" status | `meetup.MeetupCheckinService` |
| Coercion to meet somewhere private — one party pressures the other, via chat, to skip the in-app flow and meet off-platform instead | Trip lock is a hard gate on `meetups.status = 'COMPLETED'` against an allowlisted venue — there is no code path that reaches `LOCKED` based on an off-platform meeting, so the pressuring party gains nothing even if the other person agrees; chat's `OFF_PLATFORM_PUSH` moderation flag specifically watches for this pattern of message | `chat.ModerationService`, `DESIGN.md` §4 `moderation_flags` |
| Meetup-time stalking — a rejected or unrelated party learns the scheduled time/venue and shows up uninvited, or shows up early to wait | Scheduled time and venue are only ever returned by authorization-checked endpoints to the two matched parties, never through any broad or public "upcoming meetups" listing; the emergency contact's visibility into the plan is opt-in and one-directional (a safety backstop, not a third viewer with standing query access) | `meetup.MeetupController` authorization tests |

---

## Explicitly out of scope / accepted risk (document, don't silently skip)

- Real UPI payment initiation — settlements record a *confirmed outcome*
  and generate a `upi://` deep link, but TripMates never initiates a
  transfer itself via a PSP/UPI API. Doing so would reintroduce the
  money-movement liability that "the platform never holds money" exists
  to avoid (see `DESIGN.md`). If this becomes a funded product, that's a
  payments-provider integration triggering `SETTLEMENT_CONFIRMED` rows via
  a webhook instead of a manual confirm — not a ledger schema change.
- Real SMS/liveness providers — both are behind interfaces (`OtpSender`,
  `LivenessVerifier`) with a mock default implementation so the demo runs
  with zero API keys; a real provider is a new implementation, not a
  design change. No KYC vendor and no DigiLocker integration — those are
  document-verification flows, and this platform deliberately doesn't do
  document verification at all (see `DESIGN.md` → "No government ID
  verification").
- DDoS at the network layer (volumetric attacks) — out of scope for an
  application-layer threat model; would be handled by the hosting
  platform (Render/Railway) or a CDN/WAF in front of it.
