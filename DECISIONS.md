# Decisions log

One line per commit: what I built, and the key decision behind it.

- **Step 0** — Wrote DESIGN.md, THREAT_MODEL.md, PLAN.md, CLAUDE.md before
  any code. Chose UUID PKs everywhere and VARCHAR+CHECK over native
  Postgres enums, so IDOR enumeration and Flyway enum migrations both stay
  simple later.
- **Step 0** — Renamed the base package from `com.Tripmate.tripmate` to
  `com.tripmate`. Spring Initializr's default capitalization would've
  stuck around in every import otherwise.
- **Step 0** — Set up `.gitignore` secret patterns and turned off the AI
  co-author trailer. Commits need to read like my own work.
- **Step 0** — Dropped escrow/wallet custody for a settlement/IOU model.
  Realized a PPI licence requirement for holding user funds was a dead
  end for a pre-revenue project, and nobody deposits money with a new
  matching app anyway — Splitwise's model (hold nothing) fits better.
  Commitment to a trip became reputation (trust score), not money.
- **Step 0** — Added the public meet-up protocol as a required step
  before trip lock. Chat plus a video call isn't enough trust to send two
  strangers on a multi-day trip together; a real, geofenced, public
  meeting closes that gap.
- **Step 0** — Removed government ID verification entirely. The legal
  route (Aadhaar Authentication Rules 2025) needs ministry approval I
  don't have, and a document-upload step most users would refuse isn't a
  safety feature. Replaced it with selfie-match plus a vouching mechanic
  so verified users can stake some of their own trust score on someone
  else.
- **Step 1a** — Set up Postgres+PostGIS and Redis in Docker Compose, Flyway V1
  for extensions, and Testcontainers tests against the real services. Chose
  `@ServiceConnection` containers over an H2/embedded stand-in because
  PostGIS, citext and the append-only grants only exist on real Postgres. I
  also left secrets with no defaults, so a missing password stops startup
  instead of the app quietly running on a weak value.
- **Step 1b** — Added ProblemDetail error handling, a correlation-ID filter,
  ECS JSON logging, locked-down actuator, springdoc and the /api/v1 prefix.
  Chose to rebuild every error body from my own fixed strings over trusting
  Spring's defaults, and routed security 401/403 and the /error forward into
  the same handler, so there's one auditable error shape and no exception
  message can ever reach a client.
- **Step 1c** — Added security headers (strict CSP, HSTS on every response,
  nosniff, frame DENY, no-referrer, Permissions-Policy) and an exact-origin
  CORS allowlist. Chose a separate Swagger UI policy that allows its one
  inline `<style>` (the logo SVG's CSS) by sha256 hash in `style-src-elem`
  over `'unsafe-inline'`, and dropped `style-src-attr 'unsafe-inline'` once I
  confirmed DOMPurify already strips markdown style attributes. My first
  attempt missed the SVG's inline `<style>`: I'd only read
  swagger-ui-bundle.js, and the logo lives in the standalone preset. The
  tests passed, and I only caught it in the browser console, so a test now
  re-hashes the served scripts and fails when a springdoc upgrade changes
  the CSS.
- **Step 1d** — Scaffolded the Vite + React + TS frontend with Tailwind,
  Zustand, React Router and an Axios client. Chose a single-flight refresh
  (concurrent 401s share one refresh promise) on a separate interceptor-free
  Axios instance over a refresh per failed request, because the server's
  refresh-token reuse detection would read the second use of a rotated
  cookie as theft and revoke the session. The access token lives only in the
  store's closure, and a lint rule bans localStorage/sessionStorage. I pinned
  minimatch to 11.0.1 with an npm override instead of running `audit fix
  --force`, since the advisory only touched lint tooling and the forced fix
  was a downgrade to ESLint 9.
- **Step 2a (first half)** — Built the identity tables, registration and
  email verification by OTP. Chose a nullable `users.encryption_key_id` with
  a composite foreign key over a deferrable constraint, because a user really
  does exist before their first key and the composite key stops a row
  pointing at another user's key. I kept the 18+ CHECK in the database as
  well as the service, pinned to IST, since the condition only gets easier to
  satisfy over time and so survives updates and restores. Verification needs
  the `verificationId` returned to the registering client plus the emailed
  code, not the code alone, so an attacker who pre-registers someone's email
  can't get the victim to verify the attacker's account. The breach check
  fails open, because an outage at Have I Been Pwned shouldn't stop all
  sign-ups.
- **Step 2a (second half)** — Built login with Bucket4j limits per IP and
  per email, a backoff lockout, a CAPTCHA hook and `login_history` with a
  new-device alert. Every refusal is one identical 401 that runs one Argon2id
  check, against a dummy hash when the email is unknown, and the counters are
  keyed on a hash of the typed email, so neither the body, the timing nor a
  429 reveals whether an account exists. I chose a Redis Lua script that
  counts the failure before the password is checked over read-then-increment,
  so parallel guesses can't slip past the threshold. I keyed lockout per
  account and per known device cookie over per account alone, so an attacker
  failing on purpose can't lock the owner's own browser out. Login fails
  closed with a 503 when Redis is down, because the limits are the main
  defence against guessing, and the client IP comes only from Tomcat's
  RemoteIpValve trusting configured proxies, never from a raw
  X-Forwarded-For.
- **Step 2b** — Built the signup, verify-email and login screens on a Zustand
  store wired to the real identity endpoints. Chose to keep the login result
  in a separate confirmedCredentials field over faking a token or user,
  because login issues no session until 3a and route guards must never trust
  one the server didn't create. Kept the verificationId in memory only over
  putting it in the URL, since it's half of the pre-registration-hijack
  defence; a reload means signing up again, which the backend supports.
  Showed server wording only for an allowlist of problem codes over
  rendering detail directly, so no unreviewed server text reaches users, and
  exposed Retry-After through CORS so a 429 shows the real wait.
- **Step 3a (first half)** — Built 15-minute HS256 access tokens, refresh token
  rotation with reuse detection, logout, "revoke all devices" and the sessions
  list. Chose an atomic Redis Lua script for rotation over check-then-write in
  Java, so parallel refreshes can never leave two live tokens in a family, and
  gave the just-replaced token a 10-second grace that buys an access token but
  no new cookie, so two tabs racing don't sign a real user out. Chose a
  per-request check that the session key still exists over accepting the
  15-minute window or keeping a denylist: logout kills outstanding access
  tokens at once, and a lost Redis key ends a session instead of reviving one.
  Folded the planned active_sessions table into refresh_token_families so
  "is this session revoked" lives in one place, and used a required custom
  header for CSRF on /auth/refresh instead of double-submit, since a separate
  API host can't set a script-readable cookie on the app's origin.
- **Step 3a (second half)** — Built TOTP 2FA (RFC 6238 on the JDK's HMAC),
  backup codes, the second-factor login step, an admin route that needs both
  the ADMIN role and otp in amr, and an authorization matrix over every route.
  Chose a password-verified login that returns only a hashed, 5-minute,
  5-attempt Redis challenge over a password-only token with amr checks on each
  route, so no route can forget to enforce the second factor. Refused replayed
  codes with a last-used time step kept under the user row lock, because the
  ±30-second window alone lets a phished code be reused. Encrypted the TOTP
  secret with per-user data keys wrapped by an environment KEK over one global
  key, so one account can be crypto-shredded without touching the rest.
  Hashed backup codes with a keyed HMAC over Argon2id, since ten Argon2id
  checks per attempt would be a free CPU attack. Chose 404 over 403 for a
  non-owner, so an ID never confirms it exists, and made the matrix read
  routes from Spring MVC so an uncovered endpoint fails the build.
- **Step 3b** — Built the 2FA challenge screen, the security and sessions
  screens, route guards and a single-flight silent refresh with the access
  token in memory only. Chose a "starting" state that shows only a loading
  screen until the one start-up refresh answers over letting guards run on
  an empty store, so a reload never flashes the logged-out UI or bounces a
  signed-in user to /login. Chose three refresh outcomes over two: only a
  401/403 from refresh ends the session, while a 503, 429 or network error
  keeps it, so an outage never logs everyone out. Kept the second-factor
  challenge ID in memory only and accepted that a reload loses it, and
  followed return paths only when they start with a single "/".
- **Step 4a** — Built the PHONE and SELFIE_MATCH tiers, profile photos,
  vouching, the admin review queue and the outbox the trust score will read.
  Sent a phone code to any well-formed number and said "already verified
  elsewhere" only after the right code came back, so the route can't be used
  to look up which numbers have accounts. Re-encoded every upload into a new
  JPEG from its pixels over stripping EXIF tags, so nothing from the original
  file can survive, and served images through 5-minute HMAC-signed links
  because an img tag can't send a bearer token. Never showed users their
  similarity score, so the matcher can't be used to tune a borrowed photo,
  and dropped SELFIE_MATCH whenever the profile photo changes. Made
  rejection reasons a fixed list instead of reviewer text. Left the outbox
  without append-only grants for now, since the relay has to update
  processed_at, and noted that for the trust score step to settle.
- **Step 4b** — Built the verification centre (tier progress, phone code,
  profile photo, live selfie check, vouch counts), the vouch screen behind a
  SELFIE_MATCH tier guard, and the admin selfie review queue. Captured selfie
  frames only from the live camera through a canvas instead of offering a
  file picker, and put every camera call behind one module so the screen
  can be tested without a browser camera. Opened the camera before asking
  for a challenge, so a refused permission never spends an attempt. Accepted
  image links only in the server's /media/<token> shape joined to the API
  base, so no response can point an img at another origin, and kept the tier
  guard as a hint only, since the server refuses vouching below the tier.
- **Step 5a** — Built the vibe quiz (14 questions to the 8 dimensions), the
  profile and deal-breaker endpoints, and server-side quiz drafts. Kept the
  questions and their scoring numbers on the server and accepted only option
  IDs, so a client can never send its own idea of what an answer means.
  Averaged two questions per dimension with exact half-up rounding and wrote
  reversed statements with reversed numbers instead of special cases. Saved
  unfinished quizzes in a vibe_quiz_drafts table over browser storage, which
  lint already forbids, so a quiz resumes on any device. Locked the user's
  row through AccountService before every write, so two tabs submitting at
  once land as one profile instead of a key conflict, without the vibe
  module touching identity's tables.
- **Step 5b** — Built the multi-step vibe quiz and the vibe screen with hand
  adjustment and deal-breakers. Saved the answers on the server before every
  next step, so leaving mid-quiz and coming back (or switching device) picks
  up at the first unanswered step, with nothing written to browser storage.
  Dropped saved answers that no longer fit the questions instead of sending
  them back, and offered a reload when the server says the quiz changed.
  Grouped two questions per step, which keeps each step one dimension and
  each save small.
- **Step 6a** — Built trips, radius browse on PostGIS, and the join request
  flow with accept and reject. Guarded the slot claim with the trip row's
  @Version and a bounded retry instead of locking the host's account, so the
  concurrency test (8 parallel accepts at 1 slot, at the last slot of a
  part-filled trip, and at 3 slots) proves the optimistic lock alone closes
  the race. Stored plain latitude and longitude and let Postgres generate the
  geography column, so the entity needs no spatial type and the two can never
  disagree. Read other travellers only through an identity-owned
  user_public_facts view, so no trips query can reach an email, phone or
  surname.
- **Step 6b** — Built trip browse with filters, the trip create form, the
  trip page with "Ask to join", my trips, and a join request inbox with both
  sides (accept/reject on my trips, status of what I sent). Kept browse
  filters in the URL instead of component state or storage, so a search
  survives a reload and can be shared, and read them back against fixed lists
  so a crafted link cannot push an unknown value to the API. Offered a short
  list of destinations with their coordinates instead of a map widget, which
  keeps the bundle free of a map library and still lets anyone type exact
  coordinates. Refused to put any ID that is not a UUID into an API path.
- **Step 7a** — Built explainable matching: deal-breakers as SQL on both
  sides first, then a weighted soft score over the eight vibe dimensions
  with fixed-sentence reasons. Kept deal-breakers out of the pluggable
  scorer, so no scorer, rule-based or model-backed, can let a ruled-out trip
  through, and checked them again on join so a link cannot bypass a host's
  limits. Made the cache self-validating with the scorer id and both
  profiles' updated_at on every row, instead of deleting rows on a profile
  change event, because the outbox relay does not exist yet and a delete
  races with a reader writing back a score from the old profile. Embedded
  the breakdown in the trip detail response instead of a separate endpoint,
  so the trip screen stays one call.
- **Step 7b** — Built the match breakdown on the trip page (score with a
  meter, what fits and what differs under each dimension, the deal-breaker
  that rules a trip out, or a prompt to take the quiz) and the score on
  browse cards. Chose to reject a match response that contradicts itself
  (a score on a blocked match, blockers on a scored one) over rendering
  whichever half looked valid, and to drop the join button on a blocked
  match since the server would refuse it anyway.
- **Step 8a** — Built the masked chat backend: a conversation opens when a
  host accepts a companion, messages go over HTTP and are pushed over
  STOMP to each person's own queue, and every body is stripped to plain
  text, contact-redacted, then sealed under the sender's data key bound to
  its conversation and message number. Chose per-conversation message
  numbers under a row lock over timestamps, so ordering, history paging and
  unread counts never tie or skip; chose to let clients subscribe only to
  their own user queue rather than per-conversation topics, so the server
  alone decides who receives a message; and made icebreakers suggestions,
  never messages, so nothing generated is ever attributed to a person.
- **Step 8b** — Built the chat screens (inbox with live unread counts,
  conversation with history, typing, read markers, icebreaker chips and the
  contact panel) and one socket that stays open while signed in. Chose to
  write a small strict STOMP client over pulling in a library, since the
  app uses four frame types and owning the parser lets it drop anything
  malformed; chose to send messages optimistically with a client ID that a
  retry reuses, so a flaky network can never post a message twice.
