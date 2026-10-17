# AOS-E4 — More sources

| Field | Value |
|---|---|
| **Type** | Epic |
| **Status** | 🟦 In progress |
| **Priority** | P1 |
| **Owner** | Pragun |
| **ETA (rollup)** | 18h |
| **Actual** | — |
| **Target date** | TBD (AOS-005) |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **BRD reference** | §4, §6 M4 |
| **Decisions** | D-16, D-17, D-18 |
| **Depends on** | AOS-E2 |

## Goal
Gmail, WhatsApp and SMS history are captured too, so nothing is missed.

## Success criteria (exit criteria)
- [ ] Gmail syncs incrementally
- [ ] Forwarded WhatsApp messages and exported chats are ingested
- [ ] Old iPhone SMS backfilled from the Mac

## Stories
| ID | Story | Status | ETA |
|---|---|---|---|
| [AOS-S4.1](AOS-S4.1.md) | Gmail import | 🟦 | 6h |
| [AOS-S4.2](AOS-S4.2.md) | WhatsApp capture | 🟦 | 9h |
| [AOS-S4.3](AOS-S4.3.md) | SMS history backfill | ⬜ | 3h |

## Out of scope
- Android app (E6)

## Risks
| Risk | Impact | Mitigation |
|---|---|---|

## Activity log
- 2026-10-16 — Claude: epic created (AOS-004).
- 2026-10-17 — Claude: AOS-401 merged (D-59).
- 2026-10-17 — Claude: AOS-403 done (WhatsApp export import, D-66).
- 2026-10-17 — Claude: AOS-405 merged (Gmail push watch, D-63).
- 2026-10-17 — Claude: AOS-406 merged (Connect Gmail screen, D-64).
