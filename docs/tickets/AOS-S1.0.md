# AOS-S1.0 — Current, stable dependencies

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E1](AOS-E1.md) |
| **Status** | 🟦 In progress |
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

## Design (awaiting Pragun's approval)
- **Context:** most libraries are 1+ majors behind (AOS-009). The upgrades come before the
  M1 fixes so we don't fix code against old APIs twice.
  - Baseline is green: 130/130 API tests, web build OK.
- **Options considered:**
  1. Upgrade everything in one change. Rejected: failures would be hard to pinpoint.
  2. **One service per task, tests green before and after, one commit each.** Chosen.
  3. Upgrade later, after M1. Rejected: AOS-102…113 would be written against Ktor 2 /
     Exposed 0.x and then rewritten.
- **Order:** AOS-115 API → AOS-116 Web → AOS-117 PDF parser → AOS-118 Workers (needs Go,
  AOS-114) → AOS-119 images.
  - Within the API: Gradle → Kotlin → Ktor + Koin → Exposed → Flyway → the rest → Testcontainers,
    compiling and testing after each step.
- **Known breaking changes** (to be confirmed against official migration guides at upgrade time):
  - **Gradle 9:** needs JDK 17+ (we have 21); update the wrapper via `gradle wrapper`.
  - **Ktor 3:**
    - `embeddedServer` returns `EmbeddedServer`; some engine/config APIs moved.
    - `testApplication` client config changed.
    - Must pair with **Koin 4**.
  - **Exposed 1.x:**
    - Packages renamed (`org.jetbrains.exposed.sql.*` → `org.jetbrains.exposed.v1.core/jdbc.*`, java-time → `v1.javatime`).
    - `transaction {}` import moved.
    - Mechanical but touches the 6 table files and 15 repositories.
  - **Flyway 10+:** Postgres support is a separate artifact `flyway-database-postgresql`.
    This lines up with AOS-102 (Flyway owns migrations).
  - **Testcontainers 2:** module/artifact renames (e.g. `testcontainers-postgresql`); JUnit 5 integration updated.
  - **Next 15/16:**
    - React 19 is required.
    - Route `params`/`cookies()` are async (affects `/anomalies/[id]`).
    - `middleware.ts` moves to the new proxy convention (verify).
    - `next lint` is removed, so we use the ESLint CLI.
  - **Tailwind 4:**
    - CSS-first config (`@import "tailwindcss"`) and the `@tailwindcss/postcss` plugin; autoprefixer no longer needed.
    - `tailwind.config.ts` is migrated with the official upgrade tool.
  - **ESLint 9+:** flat config (`eslint.config.mjs`).
  - **TypeScript:** use the newest major that Next 16 officially supports. If 7.x isn't supported yet, use the latest supported one and note it (D-28).
  - **Python:** pin the latest versions; check FastAPI/Starlette and `python-multipart` behaviour in upload handling.
  - **Go:** `go.mod` already needs 1.25. Bump the toolchain to 1.27 and run `go get -u ./... && go mod tidy`; fix the Dockerfile.
  - **JDK:** keep building on JDK **21 LTS** locally for now. Move to **25 LTS** in AOS-119 (Docker image + Gradle toolchain) once you install JDK 25, or let Gradle download it automatically.
- **Extensibility:** move all API versions into a Gradle **version catalog**
  (`gradle/libs.versions.toml`): one place to bump, and Dependabot/Renovate-ready.
  Exact pins everywhere; lockfiles committed.
- **Impact:**
  - No behaviour change intended.
  - Tests must stay green; any test changes are API-adaptation only, never weakened assertions.
- **Rollback:** each task is one commit; revert that commit.
- **Verification per task:**
  - API: `./gradlew test` plus the integration tests.
  - Web: `next build` + lint + typecheck.
  - PDF parser: import/startup smoke test.
  - Workers: `go build ./... && go test ./...`.
  - Images: `docker compose build`.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-115](AOS-115.md) | API: Gradle 9, Kotlin 2.4, Ktor 3, Exposed 1.x, Koin 4, Flyway 13, Nimbus 10, Hikari 7, Lettuce 7, Testcontainers 2, others | Claude | ✅ | 5h | 2h |
| AOS-116 | Web: Next 16, React 19, Tailwind 4, ESLint flat config, TypeScript (supported), Node 24 LTS | Claude | ⬜ | 3h | |
| AOS-117 | PDF parser: Python and libraries (FastAPI, Uvicorn, pdfplumber, boto3, redis, python-multipart) | Claude | ⬜ | 1h | |
| AOS-118 | Workers: Go 1.27, modules up to date, fix the Dockerfile Go version | Claude | ⬜ | 1h | |
| AOS-119 | Docker/runtime images: JDK 25 LTS, Node 24 LTS, Postgres and Redis stable tags | Claude | ⬜ | 1h | |

## Activity log
- 2026-10-16 — Pragun: design approved; JDK 25 now.
- 2026-10-16 — Claude: story created from the AOS-009 audit.
