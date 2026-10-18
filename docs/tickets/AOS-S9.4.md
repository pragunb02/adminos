# AOS-S9.4 — Pay-later and trips

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E9](AOS-E9.md) |
| **Status** | ⬜ Todo |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 8h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-80 |
| **Depends on** | — |

## User story
**As** the owner, **I want** to see pay-later dues and what my trips home really cost, **so that** no hidden debt and no surprise travel spend.

## Acceptance criteria
- [ ] A pay-later bill appears in dues with its date and amount
- [ ] A trip shows bookings plus related spends and one total

## Design (fill in and get approval before coding; see docs/ENGINEERING.md §6)
Per task ticket (≥2 options each).

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-909 | Pay-later (BNPL) bills and dues from SMS/email into the dues calendar and reminders | Claude | ⬜ | 3h |  |
| AOS-910 | Trips: group travel bookings and the spends around them into one trip, with its total cost | Claude | ⬜ | 5h |  |
| [AOS-911](AOS-911.md) | Splitwise import: count only my share of shared bills; settle-ups are neither spending nor income (D-111) | Claude (agent) | ✅ | 8h | 8h |

_Task tickets are created as files when this story is picked up._

## Demo / verification
On real data after go-live (AOS-111, AOS-211/212).

## Activity log
- 2026-10-17 — Claude: story created (AOS-011 plan, D-80).
- 2026-10-18 — Claude: AOS-911 added (Splitwise import; D-111); API integrated, web next.
- 2026-10-18 — Claude: AOS-911 done (API #126, web #128).
