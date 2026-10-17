# AOS-S7.2 — Smarter data in

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E7](AOS-E7.md) |
| **Status** | ⬜ Todo |
| **Priority** | P2 |
| **Owner** | Claude |
| **ETA (rollup)** | 30h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-33, D-41, D-61 (proposed), D-67 |
| **Depends on** | AOS-S7.1 (AOS-702/703, for the message fallback only) |

## User story
**As** Pragun, **I want** messages the regexes don't recognise to be read safely, and categories that learn from my corrections, **so that** fewer transactions are missed and fewer are left as "Other".

## Acceptance criteria
- [ ] **Message fallback (AOS-705):**
  - unrecognised bank SMS, Gmail and WhatsApp messages that contain a money word are read by a schema-constrained fallback;
  - a result is accepted only if its amount, last 4 digits and date appear in the text;
  - new rows go to Review.
- [ ] **Template learning (AOS-706):** repeated message shapes (masked, never raw) become proposed parser templates, which Pragun approves.
- [ ] **Learned categoriser (AOS-707):** a local TF-IDF + logistic regression model, trained on corrections and the golden set. It never overrides a rule, and on the D-61 harness it doesn't lower any category's precision.
- [ ] **Review queue (AOS-708):** ordered by uncertainty × amount. Answers become corrections and scrubbed golden rows.

## Design
See the research report §6 and §9. The order is rules → remembered → local model (≥ threshold) → AOS-512 LLM (if on) → Other.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-705 | Message-reading fallback (SMS/Gmail/WhatsApp, Gmail bills) with grounding checks | Claude | ⬜ | 8h | |
| AOS-706 | Template learning from repeated message shapes (Drain/LILAC-style), human-approved | Claude | ⬜ | 8h | |
| AOS-707 | Local learned categoriser (Tribuo TF-IDF + LR, calibrated confidence), nightly retrain | Claude | ⬜ | 8h | |
| AOS-708 | Review queue with active learning; golden set grows from scrubbed corrections | Claude | ⬜ | 6h | |

_Task tickets are created as files when this story is picked up._

## Demo / verification
A debit SMS from a bank the parsers don't know appears in Review, with its amount highlighted in the text. The categorisation report shows fewer "Other" rows.

## Activity log
- 2026-10-17 — Claude: story created (AOS-701).
