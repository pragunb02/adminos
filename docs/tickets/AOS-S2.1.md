# AOS-S2.1 — Reliable ingestion pipeline

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E2](AOS-E2.md) |
| **Status** | ✅ Done |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 3h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-21 |

## User story
**As** Pragun (owner, India, iPhone), **I want** jobs from the API to reach workers intact, **so that** ingested data isn't lost or garbled.

## Acceptance criteria
- [x] Superseded by D-34: SMS no longer crosses Kotlin→Go; the pipeline is tested end to end on Postgres (AOS-201)

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-201](AOS-201.md) | Fix the API → worker payload contract and add contract tests | Claude | ⬜ | 3h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
A test job round-trips

## Activity log
- 2026-10-16 — Claude: story done via AOS-201 (re-scoped by D-34).
- 2026-10-16 — Claude: story created (AOS-004).
