# AOS-S7.3 — Ask your money

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E7](AOS-E7.md) |
| **Status** | ⬜ Todo |
| **Priority** | P2 |
| **Owner** | Claude |
| **ETA (rollup)** | 18h |
| **Actual** | — |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-45, D-46, D-67 |
| **Depends on** | AOS-S7.1 (AOS-702) |

## User story
**As** Pragun, **I want** to ask "how much did I spend on Swiggy in August vs July?" in plain words, **so that** I get the answer in seconds without clicking through filters.

## Acceptance criteria
- [x] The model only chooses read-only analytics tools and their typed arguments. The server injects the user and zone (AOS-710).
- [x] Every number in an answer comes from a tool result. If not, the answer is replaced by "I couldn't answer that reliably" and the raw results (AOS-710).
- [x] An eval set of about 120 synthetic questions: argument accuracy ≥ 0.9, and abstention on unanswerable questions ≥ 0.9 (AOS-712).
- [x] A web "Ask" sheet whose "How I got this" links to the matching console screen (AOS-711).

## Design
See the research report §7. Tool calling over the existing analytics API. Text-to-SQL is rejected: its error rates on benchmarks are high, and it would re-implement D-39/D-46 in generated SQL.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-712](AOS-712.md) | Ask eval set: questions, expected tool calls, seeded synthetic DB | Claude | ✅ | 4h | 4h |
| [AOS-710](AOS-710.md) | Ask API: read-only tools, period resolver, number check, cap | Claude | ✅ | 10h | 5h |
| [AOS-711](AOS-711.md) | Ask web sheet: suggestions, answer, "How I got this", feedback | Claude | ✅ | 4h | 4h |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Ask a comparison question. The numbers in the answer match the Spending screen for the same periods.

## Activity log
- 2026-10-18 — Claude (integration engineer): AOS-711 integrated onto main; D-110 recorded.
- 2026-10-18 — Claude (integration engineer): AOS-710 integrated onto main; D-109 recorded.
- 2026-10-18 — Claude (integration engineer): AOS-712 integrated onto main; D-108 recorded.
- 2026-10-17 — Claude: story created (AOS-701).
