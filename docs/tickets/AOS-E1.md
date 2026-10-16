# AOS-E1 — Foundation runs

| Field | Value |
|---|---|
| **Type** | Epic |
| **Status** | 🟦 In progress |
| **Priority** | P0 |
| **Owner** | Pragun |
| **ETA (rollup)** | 30h |
| **Actual** | — |
| **Target date** | TBD (AOS-005) |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **BRD reference** | §6 M1 |
| **Decisions** | D-8…D-14, D-21 |
| **Depends on** | AOS-E0 |

## Goal
I can run the whole app on my laptop with `docker compose up` and log in with Google.

## Success criteria (exit criteria)
- [x] `docker compose --profile full up` starts DB, API and web with no errors (AOS-110)
- [ ] Login with Google lands on the dashboard; the session survives a refresh
- [x] DB migrations apply on a fresh database (AOS-102)
- [ ] The common intake endpoint accepts a normalized event

## Stories
| ID | Story | Status | ETA |
|---|---|---|---|
| [AOS-S1.0](AOS-S1.0.md) | Current, stable dependencies | ✅ | 11h |
| [AOS-S1.1](AOS-S1.1.md) | Database starts cleanly | ✅ | 7.5h |
| [AOS-S1.2](AOS-S1.2.md) | I can log in with Google | ⬜ | 7.25h |
| [AOS-S1.3](AOS-S1.3.md) | Whole stack runs with docker compose | ✅ | 1.25h |
| [AOS-S1.4](AOS-S1.4.md) | Common intake endpoint | ⬜ | 3h |

## Out of scope
- Gmail/SMS ingestion (E2/E4), dashboards (E3)

## Risks
| Risk | Impact | Mitigation |
|---|---|---|

## Activity log
- 2026-10-16 — Claude: epic created (AOS-004).
