# AOS-S8.6 — Wealth UI

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E8](AOS-E8.md) |
| **Status** | ⬜ Todo |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 14h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-45, D-47, D-49, D-79 (proposed) |
| **Depends on** | AOS-S8.3, AOS-S8.4; AOS-S8.5 for AOS-821 |

## User story
**As** Pragun, **I want** a calm Wealth section with my net worth trend, allocation against my targets, holdings with gains, and guidance I can see the working for, **so that** I can check my position in a minute.

## Acceptance criteria
- [ ] A Wealth overview (AOS-820):
  - a net worth headline with the change vs last month and vs 12 months ago, shown with signs and words, not colour alone;
  - a monthly trend line with a table view;
  - allocation bars with a target tick and the band;
  - holdings grouped by class, with value, invested, gain, XIRR (absolute under 1 year) and price date.
- [ ] Other screens (AOS-821):
  - guidance cards with "How we got this" (inputs and dates, formula, assumptions, policy version, freshness);
  - a policy editor;
  - an imports screen with reconciliation results and undo;
  - tax report downloads behind re-auth.
- [ ] D-49: bars and lines only, no pies; Indian formatting (₹12,34,567; ₹1.2Cr on axes); the Okabe–Ito palette; axe clean; a "hide amounts" privacy mode
- [ ] Unit + Playwright tests at the D-47 bar
- [ ] Placement agreed with Pragun. The default is under More on phones and a rail item on desktop, so D-49 keeps 5 tabs

## Design
See [`WEALTH-RESEARCH.md`](../research/WEALTH-RESEARCH.md) §7 and `docs/design/console.md`. Frames are added to the design spec before coding.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-820 | Wealth overview: net worth trend, allocation vs target, holdings with gains | Claude | ⬜ | 8h | |
| AOS-821 | Guidance cards with "How we got this", policy editor, imports and tax report screens | Claude | ⬜ | 6h | |

_Task tickets are created as files when this story is picked up._

## Demo / verification
On a phone, More → Wealth shows the net worth line, the allocation bars and the holdings. The eye icon hides every amount.

## Activity log
- 2026-10-17 — Claude: story created (AOS-801).
