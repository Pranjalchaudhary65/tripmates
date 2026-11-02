# TripMates

A trust and accountability layer for travelling with strangers — not a
"travel buddy finder." Matching is the easy part; this project exists to
solve fake people, people who ghost after committing, and the lack of a
safety net during the trip itself.

> This README is a placeholder. The full version — architecture diagram,
> "Design Decisions & Trade-offs," and the OWASP Top 10 checklist — lands
> in Step 13 once the app itself is built. See `DESIGN.md`,
> `THREAT_MODEL.md`, and `PLAN.md` for the current design.

## Business model

Freemium. Verification and basic matching are free for everyone — a
safety-critical feature set shouldn't sit behind a paywall. A paid tier
unlocks advanced filters, more join requests per month, and priority
visibility on trips.

The platform earns nothing from holding funds, because it holds none.
There is no escrow and no wallet anywhere in this system — see
`DESIGN.md` → "The platform never holds money." The commitment that keeps
someone from ghosting a trip is their trust score, not a deposit; expense
splitting between members settles over UPI outside the platform, with
TripMates only tracking the resulting obligations.
