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
- [ ] The site is not reachable from the internet at all by default (AOS-616)
- [ ] A stolen Google session alone is not enough: a passkey is also required (AOS-617)
- [ ] Every route and resource is tested for authentication and ownership, including in Playwright (AOS-619)
- [ ] The owner is alerted to every new or refused sign-in (AOS-620)
- [ ] Data survives a lost laptop, encrypted (AOS-621)
- [ ] A red-team audit and a pen-test find nothing open (AOS-615, AOS-623)

## Design
Defence in depth, per D-91: network (private only) → identity (owner-only Google + passkey) → session (short, revocable, alerted) → authorization (tested per route and resource) → data (encrypted at rest and in backups) → supply chain → continuous attack tests. Details per task ticket.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-614](AOS-614.md) | Owner-only sign-in: one allowlisted Google email, pinned Google ID, fail closed (critical finding) | Claude | ✅ | 1.5h | 1.5h |
| AOS-615 | Security research, threat model and red-team audit: attack our own site (OWASP ASVS L2 / WSTG), every finding ticketed | Claude | ⬜ | 6h |  |
| AOS-616 | Private network only: Tailscale (no public listener); Cloudflare Tunnel + Access as the documented alternative | Claude | ⬜ | 3h |  |
| AOS-617 | Passkey (WebAuthn) second factor after Google, with recovery codes | Claude | ⬜ | 6h |  |
| AOS-618 | Security headers (CSP, HSTS, frame-ancestors, Referrer/Permissions-Policy), CSRF review, auth rate limits and lockout | Claude | ⬜ | 3h |  |
| AOS-619 | Authorization tests: every route requires auth unless allowlisted; every resource scoped to its owner (IDOR); Playwright attack specs | Claude | ⬜ | 4h |  |
| AOS-620 | New-sign-in and refused-sign-in alerts (push + email); sessions list and revoke-all | Claude | ⬜ | 3h |  |
| AOS-621 | Encrypted backups (client-side, separate R2 bucket, write-only token) + restore drill; key backup guide | Claude | ⬜ | 5h |  |
| AOS-622 | Supply chain and runtime: dependency and container scanning in CI (OSV, npm audit, govulncheck), non-root read-only containers, pinned images | Claude | ⬜ | 4h |  |
| AOS-623 | Self pen-test before go-live: ZAP baseline + manual checklist; fix or accept each finding | Claude | ⬜ | 4h |  |

## Demo / verification
From a device outside the private network the site doesn't answer; a second Google account is refused; the audit report lists no open findings.

## Activity log
- 2026-10-17 — Claude: story created after the critical sign-in finding (Pragun: "security is always a P0").
