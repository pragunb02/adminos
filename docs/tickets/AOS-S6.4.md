# AOS-S6.4 — Owner-only fortress

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E6](AOS-E6.md) |
| **Status** | 🟦 In progress |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 39.5h |
| **Actual** | 1.5h |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-78, D-91 |
| **Depends on** | — (blocks go-live) |

## User story
**As** the owner, **I want** nobody but me to be able to reach, sign in to, or read my AdminOS, even when it is deployed, **so that** my financial data is safe.

## Acceptance criteria
- [x] Only the owner's Google account can sign in (AOS-614)
- [x] The site is not reachable from the internet at all by default (AOS-616; Pragun sets up Tailscale at go-live)
- [ ] A stolen Google session alone is not enough: a passkey is also required (AOS-617)
- [ ] Every route and resource is tested for authentication and ownership, including in Playwright (AOS-619)
- [ ] The owner is alerted to every new or refused sign-in (AOS-620)
- [x] Data survives a lost laptop, encrypted (AOS-621; Pragun sets up the bucket and keys)
- [ ] A red-team audit and a pen-test find nothing open (AOS-615 audit done: 27 findings, being fixed; AOS-623)

## Design
Defence in depth, per D-91: network (private only) → identity (owner-only Google + passkey) → session (short, revocable, alerted) → authorization (tested per route and resource) → data (encrypted at rest and in backups) → supply chain → continuous attack tests. Details per task ticket.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-614](AOS-614.md) | Owner-only sign-in: one allowlisted Google email, pinned Google ID, fail closed (critical finding) | Claude | ✅ | 1.5h | 1.5h |
| [AOS-615](AOS-615.md) | Threat model + red-team audit: 27 findings; 2 fixed, rest ticketed (D-92) | Claude (agent) | ✅ | 6h | 4h |
| [AOS-616](AOS-616.md) | Private network only (D-97) | Claude (agent) | ✅ | 3h | 3h |
| [AOS-617](AOS-617.md) | Passkey (WebAuthn) second factor after Google, recovery codes (D-96) | Claude (agent) | ✅ | — | — |
| [AOS-618](AOS-618.md) | Hardening: nonce CSP + headers, app-wide CSRF check, sign-in state + PKCE, lockout, strict JWT, XFF distrusted (D-94) | Claude (agent) | ✅ | — | — |
| [AOS-619](AOS-619.md) | Authorization attack tests: route inventory, IDOR 404s, Playwright attack suite; exact public paths (D-93) | Claude (agent) | ✅ | — | — |
| [AOS-620](AOS-620.md) | Sign-in alerts, Security page, sign out everywhere; refresh rotation with reuse detection (also AOS-121) (D-95) | Claude (agent) | ✅ | — | — |
| [AOS-621](AOS-621.md) | Encrypted backups + restore drill (D-98) | Claude (agent) | ✅ | 5h | 4h |
| [AOS-622](AOS-622.md) | Supply chain and runtime: CI on every PR, OSV/npm audit/govulncheck/Trivy/CodeQL, Dependabot, pinned and locked-down containers (D-99) | Claude (agent) | ✅ | 4h | 6h |
| [AOS-623](AOS-623.md) | Self pen-test before go-live: ZAP baseline + scripted probes + manual checklist (D-150, D-151) | Claude | ✅ | 4h | 4h |
| [AOS-643](AOS-643.md) | Web: TRACE → 500 (accepted, D-151) | Claude | ✅ | — | — |
| [AOS-644](AOS-644.md) | Web: 404 pages under /favicon.ico had no CSP (fixed) | Claude | ✅ | 0.25h | 0.25h |
| [AOS-638](AOS-638.md) | Bill dates follow the user's calendar on an injected clock; unused Redis config removed (D-123) | Claude (agent) | ✅ | 2h | 2h |
| [AOS-637](AOS-637.md) | Worker logs the database as host:port/database only (no user info, no pgx error text) | Claude (agent) | ✅ | 1h | 1h |
| [AOS-636](AOS-636.md) | Test fixtures use reserved, obviously fake personal data + guard test | Claude (agent) | ✅ | 1h | 1h |
| [AOS-642](AOS-642.md) | Web tests: 5 s async timeout and real timers after each test | Claude | ✅ | 0.5h | 0.5h |
| [AOS-640](AOS-640.md) | Playwright in GitHub CI as the required `e2e` check (all projects, cached browsers, per-run secret, report on failure) | Claude (agent) | 🟨 | 1h | 1h |
| [AOS-630](AOS-630.md) | Gmail webhook: debounce pushes per connection; replay can't multiply syncs (AUD-27) (D-114) | Claude (agent) | ✅ | 1h | 1.5h |
| [AOS-629](AOS-629.md) | `ConnectionService` requires a `TokenEncryptor` (no plaintext path) (AUD-25) | Claude (agent) | ✅ | 0.5h | 0.5h |
| [AOS-628](AOS-628.md) | Logging hygiene: no query strings in request logs, placeholders, no SQL parameters in logs (AUD-24) | Claude (agent) | ✅ | 1h | 1h |
| [AOS-627](AOS-627.md) | Push: registering an endpoint owned by another user is refused, not moved (AUD-23) | Claude (agent) | ✅ | 0.5h | 0.5h |
| [AOS-626](AOS-626.md) | Statement parser: per-file time, page and row caps; hostile-file tests (AUD-22) (D-113) | Claude (agent) | ✅ | 2h | 1.5h |
| [AOS-625](AOS-625.md) | WhatsApp zip: cap total decompressed bytes and time across entries (AUD-20) | Claude (agent) | ✅ | 1h | 0.75h |
| [AOS-624](AOS-624.md) | Authenticate bank mail: qualify by sender only, require DMARC/aligned DKIM pass (AUD-11) (D-112) | Claude (agent) | ✅ | 3h | 2h |
| [AOS-634](AOS-634.md) | Redis requires a password; API and worker get it and refuse to start without it (D-103) | Claude (agent) | ✅ | 2h | 2h |
| [AOS-633](AOS-633.md) | Non-JSON bodies are 415, malformed JSON 400, fixed messages (never 500, never echoed) (D-102) | Claude (agent) | ✅ | 1.5h | 1.5h |
| [AOS-632](AOS-632.md) | Web image ships public/ (service worker, icons) + image check | Claude (agent) | ✅ | 1h | 1h |

## Demo / verification
From a device outside the private network the site doesn't answer; a second Google account is refused; the audit report lists no open findings.

## Activity log
- 2026-10-18 — Claude (integration engineer): AOS-623 done (owner checklist items remain for go-live); AOS-643 accepted, AOS-644 fixed.
- 2026-10-18 — Claude (integration engineer): AOS-642 (web test flake) added and done.
- 2026-10-18 — Claude: AOS-615 merged; audit follow-ups AOS-624…630 added.
- 2026-10-17 — Claude: story created after the critical sign-in finding (Pragun: "security is always a P0").
