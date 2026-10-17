# AOS-E8 — Wealth

| Field | Value |
|---|---|
| **Type** | Epic |
| **Status** | 🟦 In progress (research done; build not started) |
| **Priority** | P1 |
| **Owner** | Pragun |
| **ETA (rollup)** | 128h |
| **Actual** | 3h (AOS-801) |
| **Target date** | TBD (AOS-005) |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **BRD reference** | Scope change D-78 (wealth in scope, personal only); §7 (privacy) |
| **Decisions** | D-80 (order), D-15, D-30, D-37, D-39, D-42, D-49, D-50, D-59, D-60, D-61, D-67, D-78; D-79 (proposed in [AOS-801](AOS-801.md)) |
| **Depends on** | AOS-E2 (statement parser, pipeline), AOS-E3 (analytics), AOS-E5 (bills, mandates, vault); AOS-702 for AI explanations only |

## Goal
I see everything I own and owe in one place: holdings with real gains and XIRR, net worth over time, and calm, explainable checks against my own written investment policy. Nothing needs a broker login or an aggregator, and my data stays encrypted in my own deployment.

## Research
The full report with options, evidence and links is [`docs/research/WEALTH-RESEARCH.md`](../research/WEALTH-RESEARCH.md) (AOS-801).

## Success criteria (exit criteria)
- [ ] Encrypted offsite backups run nightly and a monthly restore drill passes, before any real holdings are stored
- [ ] Identifiers (PAN, folio, BO ID, account numbers, UAN, PRAN) are encrypted in the app with AAD. No broker or RTA credentials are stored anywhere
- [ ] A CAMS+KFintech CAS and an NSDL/CDSL CAS import, with every folio's derived units reconciling to the statement's closing units. Files and passwords are discarded (D-37)
- [ ] RSU/ESPP vests and sales, IBKR, Zerodha and Groww exports import into one ledger. Lots, realised/unrealised gains (original currency and INR) and XIRR match hand-computed golden cases
- [ ] SIP and broker debits in the bank feed are investment transfers, linked to the purchases, and no longer counted as spend
- [ ] Monthly net worth snapshots with accessible, locked and liabilities totals, staleness per line, and history
- [ ] Guidance rules evaluate the owner's versioned policy, with "how we got this" (inputs, formula, assumptions). A golden-set gate guards them
- [ ] No AI output names a security or contains a number not in its facts (D-67). Explanations are off by default
- [ ] A per-FY capital gains schedule, Schedule FA sheet and LRS tracker export. They use a tax rules table, and a test fails when the current FY has no verified rows

## Stories
| ID | Story | Status | ETA |
|---|---|---|---|
| [AOS-S8.1](AOS-S8.1.md) | Secure wealth foundation (research, model, encryption, backups, audit) | 🟦 | 19h |
| [AOS-S8.2](AOS-S8.2.md) | Holdings data in, without credentials | ⬜ | 33h |
| [AOS-S8.3](AOS-S8.3.md) | Prices, FX and calculations | ⬜ | 28h |
| [AOS-S8.4](AOS-S8.4.md) | Net worth | ⬜ | 15h |
| [AOS-S8.5](AOS-S8.5.md) | Policy-based guidance | ⬜ | 19h |
| [AOS-S8.6](AOS-S8.6.md) | Wealth UI | ⬜ | 14h |

## Plan (AOS-801 recommendation)
D-80: the E9 spending coach is built first. AOS-803 (backups) can go now, because it protects existing data. AOS-815 can be pulled forward, because it corrects E9's savings rate (AOS-908).

1. **Wave 1, "my mutual funds, safely":** AOS-803 → 802 → 805 → 810 → 811 → 812 → 820.
2. **Wave 2, "true net worth":** AOS-815 → 814 → 816 → 807.
3. **Wave 3, "my policy, checked":** AOS-817 → 818 → 821.
4. **Wave 4, completeness:** AOS-806, 808, 813 (before ITR season), 804, 819, 809.

## Out of scope
- Account Aggregator, broker/RTA logins, screen scraping behind a login, and trading-capable API tokens
- Recommending, ranking or naming securities to buy or sell; market timing; return predictions
- Filing tax returns or computing "your tax payable". The app reports gains by class for the owner or a CA
- Other users (D-78: personal only), and sharing guidance with anyone else
- Live or intraday prices

## Risks
| Risk | Impact | Mitigation |
|---|---|---|
| CAS layouts change and the parser breaks | Wrong holdings | Mandatory unit reconciliation blocks the folio; a reader port with a fallback |
| Tax rules change with every Finance Act | Wrong reports | Rules as data per FY, with source and verified date; a test fails without current-FY rows; reports say "at the rates in the table" |
| Data loss (there are no backups today) | Ledger, corrections and history lost | AOS-803 first, with a restore drill |
| Leak of identifiers or holdings | Fraud, phishing | Field encryption, localhost-only DB, encrypted backups, privacy mode, no egress from the parser |
| Double counting with the bank feed | Wrong spend and net worth | `INVESTMENT` nature + matching (AOS-815), and golden rows in the D-61 set |
| Guidance read as advice | Legal and behavioural | Owner-written policy, asset classes only, D-67 content checks, "not investment advice" text |
| Free price APIs change their terms or limits | Stale prices | A port with two adapters, bulk official files first, staleness labels |

## Activity log
- 2026-10-17 — Claude: epic created from the AOS-801 research (scope D-78); D-79 proposed in AOS-801 for the integrator.
