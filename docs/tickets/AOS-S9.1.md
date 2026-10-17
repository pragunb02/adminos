# AOS-S9.1 — Where it leaks

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E9](AOS-E9.md) |
| **Status** | ⬜ Todo |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 18h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-80 |
| **Depends on** | — |

## User story
**As** the owner, **I want** to see which habits (quick commerce, food delivery, nights out, shopping, pay-later, trips home) take my money, how often and why, **so that** I know exactly what to cut.

## Acceptance criteria
- [ ] Given a month of data, the page shows each lens with spend, orders, average order, late-night share and the change vs last month
- [ ] Every number links to the transactions behind it
- [ ] Hidden fees are totalled per app when receipts exist

## Design (fill in and get approval before coding; see docs/ENGINEERING.md §6)
Per task ticket (≥2 options each).

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-901 | Habit lenses: data-driven groups of merchants/categories (quick commerce, food delivery, nights out, shopping, pay-later, travel home), editable; catalogue additions | Claude | ⬜ | 4h |  |
| AOS-902 | Leaks API + "Where it leaks" page: spend, order count, average order, late-night share, trend vs last month, per lens and merchant | Claude | ⬜ | 6h |  |
| AOS-903 | Order-receipt emails (quick-commerce, food delivery, e-commerce): fees (delivery, handling, small-cart, platform, surge) and item counts from Gmail; matched to the payment | Claude | ⬜ | 8h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
On real data after go-live (AOS-111, AOS-211/212).

## Activity log
- 2026-10-17 — Claude: story created (AOS-011 plan, D-80).
