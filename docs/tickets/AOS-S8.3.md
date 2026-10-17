# AOS-S8.3 — Prices, FX and calculations

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E8](AOS-E8.md) |
| **Status** | ⬜ Todo |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 28h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-30, D-50, D-61, D-79 (proposed) |
| **Depends on** | AOS-S8.1 (AOS-802) |

## User story
**As** Pragun, **I want** correct values, gains and returns for every holding in INR and in its own currency, **so that** I can trust the numbers and hand a clean gains report to my CA.

## Acceptance criteria
- [ ] Daily price and FX jobs (AOS-810, D-50):
  - sources: AMFI NAVAll, NPS Trust NAV, FBIL + ECB FX (cross-checked), an SBI TT buying rate store, a US EOD adapter (Tiingo, Alpha Vantage), CoinGecko, and bhavcopy by upload;
  - valuation uses the last price on or before the date;
  - stale prices are flagged, never zeroed.
- [ ] Lots and gains (AOS-811):
  - FIFO lots handle splits, bonuses (cost 0), mergers, IDCW reinvestment, switches, vests and transfers;
  - realised and unrealised gains are in the original currency and in INR, each side converted at its own date's rate;
  - the gain is split into a price effect and an FX effect.
- [ ] Returns (AOS-812):
  - XIRR uses a bracketed solver: no unconverged numbers, and "not defined" when there is no sign change;
  - returns under 1 year are shown as absolute;
  - TWR or Modified Dietz for benchmark comparison;
  - reference cases and an NPV≈0 property test.
- [ ] Tax reports (AOS-813):
  - a per-FY tax rules table (holding months, rates, exemption, source, verified date);
  - a capital gains schedule by tax class, with the 31-Jan-2018 grandfathering and s.50AA;
  - a dividend summary, a Schedule FA calendar-year sheet (peak, closing) and an LRS tracker;
  - a test fails when the current FY has no rows.

## Design
See [`WEALTH-RESEARCH.md`](../research/WEALTH-RESEARCH.md) §2–§3. `PriceSource` and `FxSource` ports with adapters. Bulk files are preferred, so providers don't learn the holdings.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-810 | Price and FX sources, caching, holidays, staleness | Claude | ⬜ | 8h | |
| AOS-811 | Lots and gains engine: FIFO, corporate actions, multi-currency | Claude | ⬜ | 8h | |
| AOS-812 | XIRR (bracketed) + TWR | Claude | ⬜ | 4h | |
| AOS-813 | Tax reports per FY (gains schedule, Schedule FA sheet, LRS tracker) from a rules table | Claude | ⬜ | 8h | |

_Task tickets are created as files when this story is picked up._

## Demo / verification
The golden portfolios' lots, gains and XIRR match the committed hand-computed values. A holding with no new price shows "price as of …".

## Activity log
- 2026-10-17 — Claude: story created (AOS-801).
