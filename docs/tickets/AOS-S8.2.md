# AOS-S8.2 — Holdings data in, without credentials

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E8](AOS-E8.md) |
| **Status** | ⬜ Todo |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 33h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-15, D-37, D-59, D-79 (proposed) |
| **Depends on** | AOS-S8.1 (AOS-802) |

## User story
**As** Pragun, **I want** to upload the statements and exports I already get (CAS, broker, equity awards, EPF, NPS) and add the rest by hand, **so that** every holding is in one ledger without giving any app my logins.

## Acceptance criteria
- [ ] CAMS+KFintech CAS (Detailed and Summary) and NSDL/CDSL CAS parse in `pdf-parser` (casparser, pinned) and import. Derived units reconcile to the closing units per folio; otherwise the folio is blocked and shown in Review. Files and passwords are discarded (AOS-805)
- [ ] Zerodha Console holdings/tradebook and Groww exports import and reconcile to the holdings export (AOS-806)
- [ ] These import: RSU vests (FMV, shares withheld), sell-to-cover, sales, dividends with US withholding and ESPP purchases from E*TRADE/Morgan Stanley at Work, Schwab and Fidelity exports, and IBKR Flex Query XML (AOS-807)
- [ ] The EPF passbook and NPS statement parse. PPF, FD, gold/SGB, property, loans given/taken and crypto trade CSVs can be entered or imported, with estimated values labelled (AOS-808)
- [ ] Opt-in only: CAS attachments from known senders are fetched by the Gmail sync, held in memory, unlocked with an encrypted stored password, and never stored (AOS-809)
- [ ] Re-importing the same file creates no duplicates, and each import is a batch that can be undone

## Design
See [`WEALTH-RESEARCH.md`](../research/WEALTH-RESEARCH.md) §1.
- Readers in the Python service, behind a `StatementReader` port, return JSON with every number as a decimal string.
- Identifiers come back in a separate block, which the API encrypts before writing.
- Synthetic fixtures only.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-805 | CAS import: CAMS/KFintech + NSDL/CDSL via casparser; reconciliation; synthetic golden PDFs | Claude | ⬜ | 8h | |
| AOS-806 | Broker exports: Zerodha Console (holdings, tradebook, Tax P&L FMV), Groww | Claude | ⬜ | 5h | |
| AOS-807 | Equity awards (RSU/ESPP: E*TRADE/MS at Work, Schwab, Fidelity) + IBKR Flex Query XML | Claude | ⬜ | 8h | |
| AOS-808 | EPF passbook, NPS statement, PPF/FD, gold/SGB, property, loans, crypto CSV, manual entries | Claude | ⬜ | 8h | |
| AOS-809 | Opt-in CAS auto-fetch from Gmail attachments (encrypted password, memory only) | Claude | ⬜ | 4h | |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Pragun uploads a CAS locally. The import report shows each folio as "reconciled", and the holdings list matches the statement's valuation.

## Activity log
- 2026-10-17 — Claude: story created (AOS-801).
