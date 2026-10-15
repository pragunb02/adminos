# AOS-S1.1 — Database starts cleanly

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E1](AOS-E1.md) |
| **Status** | 🟦 In progress |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 7.5h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-10, D-12, D-13, D-8, D-9 |

## User story
**As** Pragun (owner, India, iPhone), **I want** the database to create all tables on a fresh start and accept writes from the API, **so that** nothing else is blocked by schema errors.

## Acceptance criteria
- [ ] Given an empty Postgres, when the API starts, then Flyway applies every migration
- [ ] Given the Exposed repositories, when their integration tests run, then inserts and reads succeed

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-100](AOS-100.md) | Schema fixes: enum union, drop hypertable, arrays → TEXT | Claude | 🟨 | 1h | 1h |
| [AOS-101](AOS-101.md) | Compile API; fix the NotificationService `when` if broken by AOS-100 | Claude | ✅ | 0.5h | 0.25h |
| [AOS-102](AOS-102.md) | Flyway owns migrations | Claude | ⬜ | 1h |  |
| [AOS-103](AOS-103.md) | JDBC `stringtype=unspecified` | Claude | ✅ | 0.5h | 0.25h |
| [AOS-104](AOS-104.md) | Run DB integration test; fix failures | Claude | ⬜ | 1.5h |  |
| [AOS-105](AOS-105.md) | Integration tests for all Exposed repositories | Claude | ⬜ | 3h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
`./gradlew test` including the integration tests is green

## Activity log
- 2026-10-16 — Claude: story created (AOS-004).
