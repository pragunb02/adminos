# AOS-E7 — Smart features (AI/ML)

| Field | Value |
|---|---|
| **Type** | Epic |
| **Status** | 🟦 In progress (research done; build not started) |
| **Priority** | P2 |
| **Owner** | Pragun |
| **ETA (rollup)** | 115h |
| **Actual** | 3h (AOS-701) |
| **Target date** | TBD (AOS-005) |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **BRD reference** | §5.1 (accuracy), §5.4 (smart extras), §7 (privacy, DPDP) |
| **Decisions** | D-3, D-33, D-57, D-61 (proposed), D-62 (in flight), D-67 |
| **Depends on** | AOS-E2, AOS-E3, AOS-E5; AOS-511 and AOS-512 (foundation, in flight) |

## Goal
AdminOS misses fewer transactions, labels more of them correctly, alerts only when it matters, answers plain-language questions and fills in documents and cash entries for me. AI never invents a number shown as fact, and my data stays on my machine unless I turn a cloud feature on (D-67).

## Research
The full report with options, evidence and links is [`docs/research/AI-ML-RESEARCH.md`](../research/AI-ML-RESEARCH.md) (AOS-701).

## Success criteria (exit criteria)
- [ ] Every AI feature goes through one provider port, is off by default, has a monthly cost cap and an audit row per call (no content), and is enabled only after its eval passes its baseline (D-67)
- [ ] Categorisation coverage rises without lowering any category's precision on the D-61 golden set (rules + local model)
- [ ] Unusual-charge alerts use robust per-merchant baselines; a dismissed false alarm stops repeating
- [ ] Unrecognised bank messages are read by a validated fallback (amount, last-4 and date grounded in the text) instead of being dropped
- [ ] "Ask your money" answers from analytics tools only; every number in an answer comes from a tool result
- [ ] Month-end forecast and "money left until salary" beat the naive pace on a backtest

## Stories
| ID | Story | Status | ETA |
|---|---|---|---|
| [AOS-S7.1](AOS-S7.1.md) | AI foundation (research, provider port, local models, consent) | 🟦 | 19h |
| [AOS-S7.2](AOS-S7.2.md) | Smarter data in (message fallback, template learning, learned categoriser, Review queue) | ⬜ | 30h |
| [AOS-S7.3](AOS-S7.3.md) | Ask your money | ⬜ | 18h |
| [AOS-S7.4](AOS-S7.4.md) | Smarter alerts and planning (robust anomalies, forecasting, subscriptions) | ⬜ | 21h |
| [AOS-S7.5](AOS-S7.5.md) | Convenience (briefing narration, documents OCR, cash from text, receipts) | 🟦 | 27h |

## Plan (AOS-701 recommendation; order by value for effort)
1. **Wave 1, no LLM:** AOS-707 → AOS-713 → AOS-708 → AOS-714.
2. **Wave 2, foundation then LLM features:** AOS-702 (after AOS-512 merges) → AOS-703 → AOS-704 → AOS-705 → AOS-712/710/711.
3. **Wave 3, convenience:** AOS-709/715 → AOS-717 → AOS-716 → AOS-718 → AOS-706 → AOS-719.

## Out of scope
- Fine-tuning LLMs; text-to-SQL; a vector database; ML for transfers, refunds or totals (D-39/D-40 stay rules)
- Any AI feature with outbound actions (sending email, paying, cancelling)
- Sending identity documents (passport, Aadhaar, PAN) to a cloud model

## Risks
| Risk | Impact | Mitigation |
|---|---|---|
| A model invents an amount or a transaction | Wrong totals, lost trust | Grounding checks, number checks, rejected outputs fall back to rules/templates (D-67) |
| Prompt injection through SMS/email text | Data leak or bad writes | No outbound tools, schema-only outputs, read-only scoped tools |
| Local models too weak on Indian bank text | Feature stays cloud-only | AOS-703 bake-off; cloud is opt-in with redaction |
| Overlap with AOS-512 | Two AI stacks | AOS-702 starts after AOS-512 and refactors it into the port |
| DPDP obligations before public launch | Legal | Per-feature consent and notice (AOS-704) feeds AOS-602 |

## Activity log
- 2026-10-17 — Claude: epic created from the AOS-701 research; D-67 proposed (awaiting Pragun).
- 2026-10-18 — Claude (agent): AOS-716 briefing narration built (D-140); PR open, awaiting review.
