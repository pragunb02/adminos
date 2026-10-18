# AOS-S9.2 — Limits and nudges

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E9](AOS-E9.md) |
| **Status** | ✅ Done (AOS-904, AOS-905, AOS-906) |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 12h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-80 |
| **Depends on** | — |

## User story
**As** the owner, **I want** to set my own limits and be told, without opening the app, how I am doing each evening and each week, **so that** I notice before the month is gone.

## Acceptance criteria
- [ ] A limit crossing pushes exactly once per threshold and period
- [ ] The evening digest arrives at the chosen time with today's numbers
- [ ] The report card makes one suggestion backed by the numbers

## Design (fill in and get approval before coding; see docs/ENGINEERING.md §6)
Per task ticket (≥2 options each).

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-904](AOS-904.md) | Limits per lens, merchant or category (monthly/weekly), pushes at 50/80/100%, once per threshold per period | Claude | ✅ | 5h |  |
| [AOS-905](AOS-905.md) | Evening digest push: today's spend, top leaks, limit status (time configurable; silent on a zero day if wanted) | Claude | ✅ | 3h |  |
| [AOS-906](AOS-906.md) | Sunday report card: week vs usual, best and worst lens, one concrete suggestion (deterministic, D-57) | Claude | ✅ | 4h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
On real data after go-live (AOS-111, AOS-211/212).

## Activity log
- 2026-10-17 — Claude: story created (AOS-011 plan, D-80).
