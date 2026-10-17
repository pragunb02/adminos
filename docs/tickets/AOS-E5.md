# AOS-E5 — Money admin & smart extras

| Field | Value |
|---|---|
| **Type** | Epic |
| **Status** | 🟦 In progress |
| **Priority** | P1 |
| **Owner** | Pragun |
| **ETA (rollup)** | 26h |
| **Actual** | — |
| **Target date** | TBD (AOS-005) |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **BRD reference** | §5.3, §5.4, §6 M5 |
| **Decisions** | D-3, D-48, D-50, D-51, D-52, D-53, D-54, D-55, D-56 |
| **Depends on** | AOS-E3 |

## Goal
The app tells me what to act on: mandates, dues, odd charges, a weekly briefing.

## Success criteria (exit criteria)
- [ ] Recurring/AutoPay tracker and dues calendar on real data — built and tested (AOS-502, AOS-503); real-data check waits on AOS-111/211
- [x] Reminders and alerts actually delivered (in-app, email, Web Push; needs SMTP/VAPID settings in `.env`)
- [ ] Weekly briefing generated from real data

## Stories
| ID | Story | Status | ETA |
|---|---|---|---|
| [AOS-S5.1](AOS-S5.1.md) | Scheduler & notifications | ✅ | 7h |
| [AOS-S5.2](AOS-S5.2.md) | Recurring payments & dues | ✅ | 8h |
| [AOS-S5.3](AOS-S5.3.md) | Alerts & weekly briefing | 🟦 | 5h |
| [AOS-S5.4](AOS-S5.4.md) | Documents vault | ⬜ | 6h |

## Out of scope
- Auto-paying or auto-cancelling

## Plan (2026-10-16, Pragun: "go go ahead"; no ETAs, as soon as possible)
Order: AOS-501 scheduler → AOS-504 notification channels (in-app, Web Push for iPhone, email) → AOS-505 unusual-charge alerts → AOS-508 bill-payment matching → AOS-502/503 recurring, mandates, dues calendar → AOS-506 weekly briefing → AOS-509 waste score → AOS-507 documents vault (needs a storage decision, D-37).

Legacy bugs found by the E5 survey, each fixed in its task:
- ~~AOS-503: custom reminder days not persisted; reminder dates on the server date; Gmail bills without a due date fail the batch~~ fixed.
- ~~AOS-505: anomaly context windows use "now" instead of the transaction's time~~ fixed.
- ~~AOS-508: card bill payments are tagged as transfers, so they never match a card bill~~ fixed.
- AOS-506/509: the queue publisher turns every payload value into a string (numbers and lists break in Go).

## Risks
| Risk | Impact | Mitigation |
|---|---|---|

## Activity log
- 2026-10-16 — Claude: epic created (AOS-004).
- 2026-10-16 — Claude: started (survey of legacy code; plan above). AOS-501 done; AOS-508, AOS-509 added.
- 2026-10-16 — Claude: S5.1 done (AOS-501, AOS-504, AOS-510).
- 2026-10-16 — Claude: AOS-505 done.
- 2026-10-16 — Claude: AOS-508 done.
- 2026-10-17 — Claude: AOS-503 done.
- 2026-10-17 — Claude: AOS-502 done; S5.1 and S5.2 complete.
