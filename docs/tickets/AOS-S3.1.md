# AOS-S3.1 — Analytics API & design

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E3](AOS-E3.md) |
| **Status** | ⬜ Todo |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 8h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-4 |

## User story
**As** Pragun (owner, India, iPhone), **I want** fast, correct aggregates, **so that** views are snappy and consistent.

## Acceptance criteria
- [ ] Daily/weekly/monthly series and breakdowns from one API

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-301](AOS-301.md) | Analytics API (series, breakdowns, comparisons) | Claude | ✅ | 4h | 2h |
| [AOS-302](AOS-302.md) | Design system: spec, tokens, formatters, 5-tab nav, period in URL (D-49) | Claude + Pragun | 🟦 | 4h |  |
| [AOS-308](AOS-308.md) | Web: shared data-fetching layer: TanStack Query (D-45) | Claude | ✅ | 3h | 1.5h |
| [AOS-120](AOS-120.md) | Per-user timezone for date-range params | Claude | ✅ | 1h | 1h |
| [AOS-310](AOS-310.md) | Web unit tests: Vitest + Testing Library + MSW, coverage gate 90/85 (D-47) | Claude | ✅ | 4h | 2.5h |
| [AOS-311](AOS-311.md) | Playwright end-to-end: 131 tests, 3 browsers + full stack (D-47) | Claude | ✅ | 5h | 2.5h |
| [AOS-312](AOS-312.md) | API coverage: 99.6% lines / 95.4% branches; gate 90/85; 18 bugs found (D-48) | Claude | ✅ | 10h | 4h |

_Task tickets are created as files when this story is picked up._

## Demo / verification
API returns series for my data

## Activity log
- 2026-10-16 — Claude: story created (AOS-004).
