# AOS-S7.1 — AI foundation

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E7](AOS-E7.md) |
| **Status** | ✅ Done |
| **Priority** | P2 |
| **Owner** | Claude |
| **ETA (rollup)** | 19h |
| **Actual** | 3h |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-67; builds on D-61 (proposed) and D-62 (in flight) |
| **Depends on** | AOS-511, AOS-512 |

## User story
**As** Pragun, **I want** every AI feature to go through one safe, switchable layer, **so that** I control what data leaves my machine and what it costs, and I know it's good enough before it runs.

## Acceptance criteria
- [x] Research report with options, evidence and a roadmap (AOS-701)
- [x] One `AiProvider` port with Anthropic and Ollama adapters and a test fake; model IDs in configuration (AOS-702)
- [x] Per-feature redaction, a monthly cost cap that fails closed, an audit row per call with no content, and flags off by default (AOS-702)
- [x] An eval gate: a feature can't be turned on unless its report meets its committed baseline (AOS-702, generalising D-61)
- [x] An optional Ollama compose profile, with local models measured on our golden sets (AOS-703)
- [ ] Settings → "AI features" lists each feature, its provider, the data it sends and this month's spend, with notice and consent text (AOS-704)

## Design
See [`docs/research/AI-ML-RESEARCH.md`](../research/AI-ML-RESEARCH.md) §2–§5. A hand-written Kotlin port and adapters (no framework). The legacy Go `internal/claude` client is retired or routed through the port.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-701](AOS-701.md) | Research: where AI/ML helps AdminOS; Epic E7; D-67 | Claude | ✅ | 3h | 3h |
| [AOS-702](AOS-702.md) | AI layer: gateway over a provider port, redaction, cost cap, audit, flags, eval gate (D-68) | Claude (agent) | ✅ | 10h | 5h |
| [AOS-703](AOS-703.md) | Local models: Ollama profile + bake-off (D-69) | Claude (agent) | ✅ | 3h | 3h |
| [AOS-704](AOS-704.md) | AI consent per feature + Settings notice (D-70) | Claude (agent) | ✅ | 3h | 3h |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Settings shows each AI feature as off, with its provider and spend. Turning one on is refused until its eval passes.

## Activity log
- 2026-10-18 — Claude: AOS-703, AOS-704 merged; AI foundation story done.
- 2026-10-18 — Claude: AOS-702 merged (D-68).
- 2026-10-17 — Claude: story created (AOS-701); AOS-701 done.
