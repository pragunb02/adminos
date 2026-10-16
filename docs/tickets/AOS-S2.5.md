# AOS-S2.5 — Numbers I can trust

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E2](AOS-E2.md) |
| **Status** | 🟦 In progress |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 15h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-20 |

## User story
**As** Pragun (owner, India, iPhone), **I want** correct totals, **so that** I trust the console.

## Acceptance criteria
- [ ] Correct debit/credit
- [x] No cross-source duplicates
- [x] Self-transfers excluded
- [x] Refunds net off
- [ ] Merchant names consistent
- [x] My category corrections remembered

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-205 | Correct debit/credit everywhere | Claude | ⬜ | 1h |  |
| AOS-206 | Deterministic merchant normalization (VPA → brand) | Claude | ⬜ | 2h |  |
| [AOS-207](AOS-207.md) | Dedup across sources | Claude | ✅ | 3h |  |
| [AOS-208](AOS-208.md) | Self-transfer detection | Claude | ✅ | 3h | 2h |
| [AOS-209](AOS-209.md) | Refund/reversal linking | Claude | ✅ | 2h | 2h |
| [AOS-210](AOS-210.md) | Single categorizer with remembered corrections | Claude | ✅ | 4h | 2h |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Monthly total matches the bank statement

## Activity log
- 2026-10-16 — Claude: AOS-208, AOS-209, AOS-210 done. Left: AOS-205, AOS-206.
- 2026-10-16 — Claude: AOS-207 done (cross-source dedup).
- 2026-10-16 — Claude: story created (AOS-004).
