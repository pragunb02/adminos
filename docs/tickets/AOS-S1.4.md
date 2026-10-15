# AOS-S1.4 — Common intake endpoint

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E1](AOS-E1.md) |
| **Status** | ⬜ Todo |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 3h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-21 |

## User story
**As** Pragun (owner, India, iPhone), **I want** every source to post one normalized event shape, **so that** new sources never touch the core (D-21).

## Acceptance criteria
- [ ] Given a valid ingest event, when posted, then it is stored and queued for processing
- [ ] The schema is documented and versioned

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-113](AOS-113.md) | Common intake endpoint (normalized ingest event) | Claude | ⬜ | 3h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
`curl` an event and see it queued

## Activity log
- 2026-10-16 — Claude: story created (AOS-004).
