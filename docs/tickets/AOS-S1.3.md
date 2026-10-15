# AOS-S1.3 — Whole stack runs with docker compose

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E1](AOS-E1.md) |
| **Status** | ⬜ Todo |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 1.25h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-8 |

## User story
**As** Pragun (owner, India, iPhone), **I want** one command to start everything, **so that** running it is effortless.

## Acceptance criteria
- [ ] Given a .env, when I run `docker compose up`, then postgres, api and web are healthy
- [ ] Workers build (Go installed locally for tests)

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-110](AOS-110.md) | Add services/api/Dockerfile; fix compose | Claude | ⬜ | 1h |  |
| [AOS-114](AOS-114.md) | Install Go locally | Pragun | ⬜ | 0.25h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
`docker compose up` then open localhost:3000

## Activity log
- 2026-10-16 — Claude: story created (AOS-004).
