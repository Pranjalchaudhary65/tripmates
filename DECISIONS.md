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
