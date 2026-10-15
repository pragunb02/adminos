# AOS-S2.1 — Reliable ingestion pipeline

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E2](AOS-E2.md) |
| **Status** | ⬜ Todo |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 3h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-21 |

## User story
**As** Pragun (owner, India, iPhone), **I want** jobs from the API to reach workers intact, **so that** ingested data isn't lost or garbled.

## Acceptance criteria
- [ ] Contract tests between the Kotlin publisher and the Go handlers pass

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-201 | Fix the API → worker payload contract and add contract tests | Claude | ⬜ | 3h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
A test job round-trips

## Activity log
- 2026-10-16 — Claude: story created (AOS-004).
