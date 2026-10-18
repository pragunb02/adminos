# AOS-S7.4 — Smarter alerts and planning

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E7](AOS-E7.md) |
| **Status** | ⬜ Todo |
| **Priority** | P2 |
| **Owner** | Claude |
| **ETA (rollup)** | 21h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-53, D-55, D-56, D-58, D-65, D-67 |
| **Depends on** | — (no LLM needed) |

## User story
**As** Pragun, **I want** alerts that know what's normal for each merchant, an honest month-end forecast, and one subscription per real service, **so that** I act on the few things that matter.

## Acceptance criteria
- [x] **Robust alerts (AOS-713):**
  - large-amount alerts use a per-merchant median/MAD baseline (else per category) and fire at a modified z-score above 3.5;
  - the alert text shows the usual range;
  - a dismissal raises that merchant's tolerance (capped), and a confirmation lowers it.
- [x] **Forecast (AOS-714):**
  - month-end = spent so far + known unpaid dues and mandates + a robust run-rate, with an empirical range;
  - "money left until salary" when the salary and balance are known;
  - "not enough history" with less than 3 months of data.
- [x] **Merchant grouping (AOS-709):** uncatalogued merchant names that are the same service are proposed for merging in Review.
- [x] **Price-rise alert (AOS-715):** when a charge is at least max(5%, ₹20) above the median of the previous 3, once per new price.

## Design
See the research report §10–§12. Isolation Forest, Prophet and deep forecasting are rejected for sparse personal data.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-714](AOS-714.md) | Month-end forecast + money left until salary (deterministic + empirical range) | Claude | ✅ | 8h | 9h |
| [AOS-713](AOS-713.md) | Robust anomaly baselines (median/MAD per merchant) + learning from dismissals | Claude | ✅ | 6h | 5h |
| [AOS-709](AOS-709.md) | Fuzzy merchant grouping for subscriptions (normalise + Jaro-Winkler), merges via Review | Claude | ✅ | 5h | 5h |
| [AOS-715](AOS-715.md) | Subscription price-rise alert | Claude | ✅ | 2h | 1.5h |

_Task tickets are created as files when this story is picked up._

## Demo / verification
A synthetic spending stream with injected spikes alerts on the spikes, and otherwise at most twice a month. The backtest beats the naive pace.

## Activity log
- 2026-10-18 — Claude (integration engineer): AOS-714 integrated onto main; D-76 recorded.
- 2026-10-18 — Claude (integration engineer): AOS-709 integrated onto main; D-75 recorded.
- 2026-10-18 — Claude (integration engineer): AOS-715 integrated onto main; D-74 recorded.
- 2026-10-18 — Claude (integration engineer): AOS-713 integrated onto main; D-73 recorded.
- 2026-10-17 — Claude: story created (AOS-701).
