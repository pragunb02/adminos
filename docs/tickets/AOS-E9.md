# AOS-E9 — Spending coach

| Field | Value |
|---|---|
| **Type** | Epic |
| **Status** | 🟦 In progress |
| **Priority** | P0 |
| **Owner** | Pragun |
| **ETA (rollup)** | 47h |
| **Actual** | — |
| **Target date** | TBD (AOS-005) |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **BRD reference** | BRD §1 (north star), §5.6 |
| **Decisions** | D-57, D-67, D-78, D-80 |
| **Depends on** | AOS-E3 (console), AOS-E5 (notifications) |

## Goal
The app changes behaviour, not just records it: I see where money leaks, get told without opening the app, set limits and goals, and watch my savings rate rise. Step one of "spend less → save more → invest well" (D-80).

## Success criteria (exit criteria)
- [ ] "Where it leaks" shows every habit lens with its numbers, each traceable to transactions
- [ ] Limits push at 50/80/100% exactly once per period
- [ ] An evening digest and a Sunday report card arrive without opening the app
- [ ] The savings rate is the Home headline, with its trend
- [ ] On real data: the owner's monthly leak spend falls for 3 months in a row (the real test)

## Stories
| ID | Story | Status | ETA |
|---|---|---|---|
| [AOS-S9.1](AOS-S9.1.md) | Where it leaks | ⬜ | 18h |
| [AOS-S9.2](AOS-S9.2.md) | Limits and nudges | ✅ | 12h |
| [AOS-S9.3](AOS-S9.3.md) | Goals and savings rate | ✅ | 9h |
| [AOS-S9.4](AOS-S9.4.md) | Pay-later and trips | ⬜ | 8h |

## Out of scope
- Blocking apps or payments (the coach informs; it never acts on the owner's behalf).
- Investment advice (Epic E8).

## Risks
| Risk | Impact | Mitigation |
|---|---|---|
| Nudges become noise and get ignored | The coach stops working | One digest a day, one report a week, limits only once per threshold; every push is configurable |
| Wrong categories make the leaks wrong | Trust lost | Lenses build on the categoriser and its eval gate (D-61); every number links to its transactions |

## Activity log
- 2026-10-17 — Claude: epic created (Pragun: the primary goal is to stop wasting money and build wealth, D-80).
