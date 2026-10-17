# AOS-S8.4 — Net worth

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E8](AOS-E8.md) |
| **Status** | ⬜ Todo |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 15h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-33, D-39, D-42, D-45, D-46, D-50, D-54, D-56, D-79 (proposed) |
| **Depends on** | AOS-S8.1, AOS-S8.2 (AOS-805), AOS-S8.3 (AOS-810) |

## User story
**As** Pragun, **I want** one net worth figure with its history, built from what I own and owe, **so that** I see progress month by month without anything counted twice.

## Acceptance criteria
- [ ] Balances and liabilities (AOS-814):
  - balance observations come from statements and SMS "Avl Bal" lines (structured fields only, D-33);
  - liabilities come from unpaid card bills plus later card spends, and from manual loans.
- [ ] A new `INVESTMENT` nature (AOS-815):
  - SIP, broker, clearing, NPS, PPF, FD and LRS debits, and redemption and remittance credits, are transfers;
  - they are matched to ledger rows (amount less stamp duty, T−1…T+3), and unmatched ones are shown in Review;
  - dividends are income;
  - `INVESTMENT` rows are excluded from spent and received;
  - golden rows are added to the D-61 set.
- [ ] Monthly snapshots (AOS-816):
  - immutable, with accessible, locked and liabilities totals, and lines with a price date and a stale flag;
  - a correction writes a new version;
  - history is backfilled from the ledger and the price history.

## Design
AOS-815 also corrects E9's savings rate (AOS-908, D-80): SIP debits must not count as spend.
See [`WEALTH-RESEARCH.md`](../research/WEALTH-RESEARCH.md) §4. An `InvestmentFlowStep` runs after `TransferStep` (D-34 pipeline). The payee list is data (D-44 style).

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-814 | Balance observations + liabilities (card dues, loans) | Claude | ⬜ | 5h | |
| AOS-815 | Investment flows in the bank feed: `INVESTMENT` nature, matching, no double counting | Claude | ⬜ | 6h | |
| AOS-816 | Net worth snapshots (monthly job), versioning, backfill | Claude | ⬜ | 4h | |

_Task tickets are created as files when this story is picked up._

## Demo / verification
In a month with a ₹10,000 SIP, spend is unchanged by it, "invested ₹10,000" is shown, and the snapshot's bank and MF lines move by the same amount.

## Activity log
- 2026-10-17 — Claude: story created (AOS-801).
