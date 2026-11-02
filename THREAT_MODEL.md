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
- API ↔ Object storage (ID docs, photos — never served directly to the browser)
- API ↔ pluggable AI adapter (treated as untrusted output generator — never executes anything, never gets secrets, output is always sanitized before storage/display)
- Mobile/web client ↔ WebSocket (same auth boundary as REST, often forgotten)

---

## S — Spoofing

| Threat | Mitigation | Where |
|---|---|---|
| Attacker brute-forces/guesses passwords | Argon2id hashing (tuned cost), breached-password check via k-anonymity range query against HaveIBeenPwned-style API, per-IP + per-account rate limiting with exponential backoff, CAPTCHA after N failures | `identity.AuthService`, `Bucket4jConfig`, `ratelimit` interceptor |
| Stolen/replayed refresh token used to impersonate a user indefinitely | Refresh token **rotation with reuse detection** — a token used twice revokes the entire token family, forcing re-login on every device in that family | `identity.RefreshTokenService`, Redis `refresh:{familyId}` key, `refresh_token_families` table |
| Attacker fakes a trusted identity (fake ID doc photo, someone else's selfie) | Tiered verification is explicitly a *confidence score*, not a boolean "verified" — selfie match tier requires the selfie to match the submitted ID photo (mock verifier in dev, pluggable real verifier interface for prod); trust score formula weights SELFIE_MATCH far higher than EMAIL-only, so the UI and matching never treat all "verified" users as equivalent | `identity.verification`, `DESIGN.md` Trust Score spec |
| CSRF — a malicious page submits a cookie-authenticated request on behalf of a logged-in user | Access token is a bearer token the SPA attaches explicitly (not auto-sent); only the refresh token lives in a cookie, and that cookie is `httpOnly + Secure + SameSite=Strict` plus a double-submit CSRF token required on the one endpoint that reads it (`/auth/refresh`) | `SecurityConfig`, `CsrfTokenFilter` |
| WebSocket connection impersonates another user | JWT access token required and validated on STOMP `CONNECT`; every `SUBSCRIBE` is authorized against the principal's own conversation/trip membership before the subscription is accepted, not just at connect time | `chat.StompAuthChannelInterceptor` |

## T — Tampering

| Threat | Mitigation | Where |
|---|---|---|
| Client sends a manipulated escrow/expense `amount` | Server never trusts a client-supplied amount for anything that affects a ledger entry — amounts are either server-computed (trip's fixed commitment amount) or validated against a server-side expected value before any `ledger_entries` insert | `escrow.EscrowService`, `escrow.ExpenseService` |
| Two concurrent requests corrupt a balance or overfill a trip slot | Optimistic locking (`@Version`) on `trips` and `escrows`; append-only ledger means there is no mutable balance to corrupt in the first place — balance is always `SUM(ledger_entries)` | `DESIGN.md` §Trips, §Escrow |
| Attacker edits historical audit/ledger/transition rows directly (DB access, insider, bug) | Append-only tables have no `UPDATE`/`DELETE` grant for the app's DB role; `audit_log` is additionally hash-chained so even a privileged edit breaks `row_hash` continuity, caught by a scheduled integrity sweep | `DESIGN.md` §Append-only enforcement, flyway migration granting privileges |
| Mass-assignment — client sends extra JSON fields (`role: "ADMIN"`, `trustScore: 100`) that get bound onto an entity | Every endpoint binds to a dedicated request DTO with Bean Validation, never a JPA entity directly; MapStruct maps DTO → entity explicitly field-by-field, so an unmapped field is simply dropped, not silently persisted | All `*.dto.*Request` classes, MapStruct mappers |
| Tampered JWT (algorithm confusion, modified claims) | Signing key fixed server-side (HMAC-SHA256 or RSA, algorithm pinned — `alg: none` and mismatched-algorithm tokens rejected explicitly), short 15-minute expiry limits the damage window of a key compromise | `identity.JwtService` |

## R — Repudiation

| Threat | Mitigation | Where |
|---|---|---|
| User denies triggering an escrow forfeiture / admin denies suspending an account | `audit_log` hash chain records every auth event, safety event, money movement, and admin action with `actor_user_id`, timestamp, and IP, in the same transaction as the change — not best-effort logging after the fact | `platform.AuditLogService`, `DESIGN.md` §8 |
| User claims "I never saw that review"/"I didn't ghost" | `escrow_state_transitions` and `trust_score_snapshots` keep a full timestamped history independent of current row state, so any score or escrow dispute can be reconstructed exactly as it happened | `DESIGN.md` §Escrow state machine, §Trust Score spec |
| "The AI said something inappropriate, not me" (icebreaker/moderation messages) | AI/system-generated chat messages are stored with `sender_id = NULL` and a distinguishable type, never attributed to a real user, and are themselves subject to the same sanitization pipeline before storage | `chat.ChatMessage` entity |

## I — Information Disclosure

| Threat | Mitigation | Where |
|---|---|---|
| IDOR — user requests `/api/trips/{id}/escrow` or `/api/users/{id}/reviews` for an ID that isn't theirs | Deny-by-default `@PreAuthorize` plus an explicit ownership check inside the service (never trust the URL path alone); **every** endpoint has an authorization test asserting owner-allowed / non-owner-denied / anonymous-denied | `*.controller.*`, `*AuthorizationTest` suite |
| Phone number, ID document number, emergency contact, precise location leak via a DB dump, log file, or API response | Field-level AES-GCM envelope encryption at rest for these fields; blind-index (HMAC) column for the one field (`phone`) that must stay searchable; PII masked before any log statement, correlation IDs used instead of raw identifiers in logs | `DESIGN.md` §1 `users`, §6 `itinerary_shares`/`checkins`, `platform.logging.PiiMaskingConverter` |
| A photo upload leaks a user's home address via embedded EXIF GPS metadata | Every uploaded image is re-encoded through a library that strips all EXIF/metadata before storage; original bytes are never persisted | `identity.upload.ImageSanitizer` |
| Uploaded ID document served directly from a predictable/public URL | Files are stored outside the web root under randomized (UUID) names; the only access path is a short-lived signed URL issued per-request to an authorized viewer (the user themselves or an admin reviewer) | `identity.upload.SignedUrlService` |
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

---

## Explicitly out of scope / accepted risk (document, don't silently skip)

- Real payment gateway integration — escrow is a simulated wallet. If this
  becomes real money, PCI-DSS scope and a licensed payment processor are
  required; the ledger design is deliberately gateway-agnostic so swapping
  in a real processor later means adding a new `reference_type`, not a
  schema rewrite.
- Real SMS/ID-verification providers — both are behind interfaces
  (`OtpSender`, `IdentityVerifier`) with a mock default implementation so
  the demo runs with zero API keys; a real provider is a new
  implementation, not a design change.
- DDoS at the network layer (volumetric attacks) — out of scope for an
  application-layer threat model; would be handled by the hosting
  platform (Render/Railway) or a CDN/WAF in front of it.
