# AOS-S8.5 — Policy-based guidance

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E8](AOS-E8.md) |
| **Status** | ⬜ Todo |
| **Priority** | P1 |
| **Owner** | Claude + Pragun |
| **ETA (rollup)** | 19h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-57, D-61, D-67, D-79 (proposed) |
| **Depends on** | AOS-S8.3 (AOS-812), AOS-S8.4 (AOS-816); AOS-702 for AOS-819 |

## User story
**As** Pragun, **I want** my own written investment policy checked against my real portfolio, with the arithmetic shown, **so that** I know when to rebalance, top up the emergency fund or cut fees, without anyone picking securities for me.

## Acceptance criteria
- [ ] A structured, versioned Investment Policy Statement written by Pragun (AOS-817). It holds:
  - targets that sum to 100, with absolute and relative bands;
  - emergency months and the essential categories;
  - concentration and employer-stock limits;
  - fee ceilings and an idle-cash limit;
  - goals and the stated assumptions.
- [ ] Rules are `GuidanceRule` strategies (AOS-818):
  - allocation drift and bands, with cash-flow rebalancing first;
  - emergency months, from the 12-month median of essential spend;
  - concentration, including unvested employer exposure;
  - the direct-vs-regular TER gap of the same scheme, with the switching cost;
  - idle cash;
  - the goal SIP, with sensitivity.
  - Each result stores its facts, formula and assumptions, and the policy and ledger versions.
- [ ] A synthetic golden set of portfolios × policies with a no-change gate, D-61 style (AOS-818)
- [ ] An optional AI explanation (AOS-819):
  - built from the facts object only;
  - a number check, and a forbidden-content check (no securities, tickers, buy/sell instructions or return promises);
  - a template fallback;
  - off by default and local-first;
  - an eval set with injection traps.

## Design
See [`WEALTH-RESEARCH.md`](../research/WEALTH-RESEARCH.md) §5. Asset classes only, and no "buy" actions. The cards say "Planning support from your own policy. Not investment advice."

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-817 | Investment Policy Statement: structured, versioned storage + validation | Claude + Pragun | ⬜ | 4h | |
| AOS-818 | Guidance rules engine + golden set gate | Claude | ⬜ | 10h | |
| AOS-819 | AI explanations under D-67 (facts only, number and content checks), off by default | Claude | ⬜ | 5h | |

_Task tickets are created as files when this story is picked up._

## Demo / verification
With a policy of 45% Indian equity ±5 and a portfolio at 52%, the card says "above band" and how many months of redirected SIP would fix it. "How we got this" shows the inputs and the formula.

## Activity log
- 2026-10-17 — Claude: story created (AOS-801).
