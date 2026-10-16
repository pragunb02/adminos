# AOS-E2 — Data in (no typing)

| Field | Value |
|---|---|
| **Type** | Epic |
| **Status** | 🟦 In progress |
| **Priority** | P0 |
| **Owner** | Pragun |
| **ETA (rollup)** | 31h |
| **Actual** | — |
| **Target date** | TBD (AOS-005) |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **BRD reference** | §4, §5.1, §6 M2 |
| **Decisions** | D-16, D-19, D-20, D-21 |
| **Depends on** | AOS-E1 |

## Goal
My transactions arrive automatically from statements and iPhone SMS, plus cash quick-add, and the numbers are correct.

## Success criteria (exit criteria)
- [ ] A statement upload creates correct transactions (debit/credit, date, merchant)
- [ ] A bank SMS on my iPhone shows up within a minute
- [ ] Self-transfers and refunds don't distort totals
- [ ] No duplicates across sources

## Stories
| ID | Story | Status | ETA |
|---|---|---|---|
| [AOS-S2.1](AOS-S2.1.md) | Reliable ingestion pipeline | ✅ | 3h |
| [AOS-S2.2](AOS-S2.2.md) | Import bank statements | 🟦 (AOS-212 left) | 6h |
| [AOS-S2.3](AOS-S2.3.md) | iPhone SMS auto-capture | 🟦 (AOS-211 left) | 5h |
| [AOS-S2.4](AOS-S2.4.md) | Cash quick-add | ✅ | 2h |
| [AOS-S2.5](AOS-S2.5.md) | Numbers I can trust | 🟨 code done | 15h |

## Out of scope
- Gmail, WhatsApp (E4)

## Risks
| Risk | Impact | Mitigation |
|---|---|---|

## Activity log
- 2026-10-16 — Claude: epic created (AOS-004).
