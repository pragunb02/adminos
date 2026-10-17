# AOS-S4.1 — Gmail import

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E4](AOS-E4.md) |
| **Status** | 🟦 In progress |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 6h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-18 |

## User story
**As** Pragun (owner, India, iPhone), **I want** bank alerts and receipts from Gmail, **so that** email-only spends are captured.

## Acceptance criteria
- [ ] Incremental sync; full body; tokens encrypted

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-401](AOS-401.md) | Gmail incremental sync, full body, sender filters, encrypted tokens, fallback job | Claude (agent) | 🟨 | 6h | 4.5h |
| [AOS-405](AOS-405.md) | Gmail push: `users.watch` after connect, 6-hourly renewal, `users.stop` on disconnect (D-63) — live check needs AOS-111 | Claude (agent) | 🟨 | 4h | 3h |
| [AOS-406](AOS-406.md) | Web Connect Gmail: callback page, Settings buttons, server-side single-use OAuth state + PKCE (D-64) — live check needs AOS-111 | Claude (agent) | 🟨 | 5h | 3.5h |

_Task tickets are created as files when this story is picked up._

## Demo / verification
A new receipt email appears

## Activity log
- 2026-10-17 — Claude: AOS-406 merged (Connect Gmail screen, D-64).
- 2026-10-17 — Claude: AOS-405 merged (Gmail push watch, D-63).
- 2026-10-16 — Claude: story created (AOS-004).
- 2026-10-17 — Claude: AOS-401 merged (🟨 until a live Google check, AOS-111). Follow-ups: `users.watch` registration/renewal; web "Connect Gmail" with OAuth state check.
