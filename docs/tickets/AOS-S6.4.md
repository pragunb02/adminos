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
| AOS-623 | Self pen-test before go-live: ZAP baseline + manual checklist; fix or accept each finding | Claude | ⬜ | 4h |  |
| AOS-624 | Authenticate bank mail: qualify by sender only, require DMARC/aligned DKIM pass (AUD-11) | Claude | ⬜ | 3h |  |
| AOS-625 | WhatsApp zip: cap total decompressed bytes and time across entries (AUD-20) | Claude | ⬜ | 1h |  |
| [AOS-626](AOS-626.md) | Statement parser: per-file time, page and row caps; hostile-file tests (AUD-22) (D-113) | Claude (agent) | ✅ | 2h | 1.5h |
| AOS-627 | Push: registering an endpoint owned by another user is refused, not moved (AUD-23) | Claude | ⬜ | 0.5h |  |
| AOS-628 | Logging hygiene: no query strings in request logs, placeholders, no SQL parameters in logs (AUD-24) | Claude | ⬜ | 1h |  |
| AOS-629 | `ConnectionService` requires a `TokenEncryptor` (no plaintext path) (AUD-25) | Claude | ⬜ | 0.5h |  |
| AOS-630 | Gmail webhook: debounce pushes per connection; replay can't multiply syncs (AUD-27) | Claude | ⬜ | 1h |  |

## Demo / verification
From a device outside the private network the site doesn't answer; a second Google account is refused; the audit report lists no open findings.

## Activity log
- 2026-10-18 — Claude: AOS-615 merged; audit follow-ups AOS-624…630 added.
- 2026-10-17 — Claude: story created after the critical sign-in finding (Pragun: "security is always a P0").
