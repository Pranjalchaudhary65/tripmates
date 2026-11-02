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
