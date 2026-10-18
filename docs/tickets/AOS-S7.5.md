# AOS-S7.5 — Convenience

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E7](AOS-E7.md) |
| **Status** | 🟦 In progress |
| **Priority** | P3 |
| **Owner** | Claude |
| **ETA (rollup)** | 27h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-37, D-42, D-57, D-60, D-67 |
| **Depends on** | AOS-S7.1 (AOS-702/703) |

## User story
**As** Pragun, **I want** documents, receipts and cash entries filled in for me, and a briefing that reads well, **so that** I type less without having to trust a guess.

## Acceptance criteria
- [x] **Briefing (AOS-716):** the rewrite is optional. Any number or name that isn't in the facts rejects it, and the template is sent instead (🟨 awaiting review; D-140).
- [x] **Documents (AOS-717):** (🟨 awaiting review; D-173, D-174, D-175)
  - use the PDF's text layer, else local OCR in the Python service (Tesseract);
  - fields are proposed with their source snippet and saved only after I confirm;
  - identity documents never go to a cloud model (in fact to no model at all).
- [x] **Cash (AOS-718):** from free text or keyboard dictation ("chai 40, auto 120 yesterday"), with a confirm sheet, through `CashIntake`.
- [ ] **Receipts (AOS-719):** a receipt photo is matched to an existing card/UPI debit (amount ±1%, date ±2 days, similar merchant) and attached as evidence, not saved as a second spend.

## Design
See the research report §8, §13 and §14.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-716](AOS-716.md) | Briefing narration with a number check (falls back to the template) | Claude | 🟨 | 4h | 5h |
| [AOS-717](AOS-717.md) | Documents vault OCR + field proposals, local-first | Claude (agent) | 🟨 | 10h | 12h |
| [AOS-718](AOS-718.md) | Cash quick-add from free text or dictation | Claude | ✅ | 5h | 6h |
| AOS-719 | Receipt photo → matched to the card/UPI debit | Claude | ⬜ | 8h | |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Upload a PUC photo. The expiry date is proposed with its snippet, and confirming it schedules the reminder.

## Activity log
- 2026-10-18 — Claude (integration engineer): AOS-718 integrated onto main; D-77 recorded.
- 2026-10-18 — Claude (agent): AOS-716 built (D-140, V90); PR open, awaiting review.
- 2026-10-18 — Claude (agent): AOS-717 built (PR #158; D-173, D-174, D-175; V80). The Trivy exception for libxml2 was accepted by Pragun (D-174). Awaiting review.
- 2026-10-17 — Claude: story created (AOS-701).
