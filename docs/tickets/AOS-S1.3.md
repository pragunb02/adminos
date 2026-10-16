# AOS-S1.3 — Whole stack runs with docker compose

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E1](AOS-E1.md) |
| **Status** | ✅ Done |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 1.25h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-8 |

## User story
**As** Pragun (owner, India, iPhone), **I want** one command to start everything, **so that** running it is effortless.

## Acceptance criteria
- [x] Given a .env, when I run `docker compose --profile full up`, then postgres, api and web are healthy (AOS-110)
- [x] Workers build and run (Go via Docker, AOS-118; local Go optional, AOS-114)

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-110](AOS-110.md) | API Dockerfile (JDK 25, non-root, healthcheck); compose waits for API | Claude | ✅ | 1h | 0.75h |
| [AOS-114](AOS-114.md) | Install Go locally | Pragun | ⬜ | 0.25h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
`docker compose up` then open localhost:3000

## Activity log
- 2026-10-16 — Claude: story done (AOS-110; AOS-114 optional).
- 2026-10-16 — Claude: story created (AOS-004).
