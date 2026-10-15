# AOS-S1.0 — Current, stable dependencies

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E1](AOS-E1.md) |
| **Status** | ⬜ Todo |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 11h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-28 |
| **Depends on** | AOS-101 (green baseline) |

## User story
**As** Pragun, **I want** every service on the latest stable versions, **so that** new code is built on supported, secure foundations and we don't redo work after upgrading later.

## Acceptance criteria
- [ ] Every dependency is at its latest stable (or newest officially supported combination), pinned
- [ ] All tests and builds are green after each service's upgrade
- [ ] Docker images build

## Design (to be filled in and approved before coding)
- Order: API → web → pdf-parser → workers → images. One commit per service.
- Ktor 3, Exposed 1.x and Flyway 13 have breaking API changes; the migration notes go in each task.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-115 | API: Gradle 9, Kotlin 2.4, Ktor 3, Exposed 1.x, Koin 4, Flyway 13, Nimbus 10, Hikari 7, Lettuce 7, Testcontainers 2, others | Claude | ⬜ | 5h | |
| AOS-116 | Web: Next 16, React 19, Tailwind 4, ESLint flat config, TypeScript (supported), Node 24 LTS | Claude | ⬜ | 3h | |
| AOS-117 | PDF parser: Python and libraries (FastAPI, Uvicorn, pdfplumber, boto3, redis, python-multipart) | Claude | ⬜ | 1h | |
| AOS-118 | Workers: Go 1.27, modules up to date, fix the Dockerfile Go version | Claude | ⬜ | 1h | |
| AOS-119 | Docker/runtime images: JDK 25 LTS, Node 24 LTS, Postgres and Redis stable tags | Claude | ⬜ | 1h | |

## Activity log
- 2026-10-16 — Claude: story created from the AOS-009 audit.
