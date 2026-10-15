# AdminOS — context for Claude sessions

AdminOS is Pragun's **personal money console**. It collects transactions from bank
statements, manual entry, Gmail, SMS and WhatsApp, and shows where money goes each
day, week and month. It's built for Pragun first and must stay extensible to public users.

## Read these first
0. `docs/BACKLOG.md`: the board (Epic → Story → Task tree with status and ETA). Ticket files are in `docs/tickets/`.
1. `docs/BRD.md`: the goal, inputs and outputs, milestones, and scope.
2. `docs/PROGRESS.md`: the current state of the code and business logic, the active branch, and the next step.
3. `docs/DECISIONS.md`: past decisions and why they were made. Don't re-litigate accepted ones.
4. `docs/ENGINEERING.md`: **mandatory** architecture layers, coding rules, testing standards and Definition of Done.

## Working rules (mandatory)
- **Before any change** (edit, build, branch, install, commit), state **what you're trying to do and the next step**, then wait for approval.
  - Read-only exploration doesn't need approval.
- Work in small steps on a feature branch; never commit to `main` directly.
- Commit only when asked.
- **Tickets (D-25):** Epic `AOS-E<n>` → Story `AOS-S<n>.<m>` → Task `AOS-<n><nn>`. New tickets copy `docs/tickets/_templates/`.
  - Before starting a task: create its file if missing, set it to 🟦 and add an Activity log entry.
  - When done: fill in Resolution, Actual and Done, tick the acceptance criteria, and update the story, the epic and the BACKLOG.md row.
- **Every change has a task ID** (add the ticket first if it's missing).
  - Use the ID in the branch (`aos-102-…`), the commit (`AOS-102: …`), PROGRESS entries and decisions.
- Each decision in DECISIONS.md states its **Type** (Architecture / Product / Scope / ETA / Process) and linked tasks, and is added to the index.
- Before ending a session, update `docs/PROGRESS.md`: what changed, what was verified, and the next step.
- Record any meaningful choice in `docs/DECISIONS.md` (append only).
- Code follows `docs/ENGINEERING.md`. A task is done only when its Definition of Done is met.
- If you find a bug, report it and mention it in PROGRESS.md; don't silently fix things outside the agreed step.

## Stack
| Part | Path | Tech | Verify |
|---|---|---|---|
| API | `services/api` | Kotlin + Ktor, Exposed, Flyway | `./gradlew test` |
| Workers | `services/workers` | Go + Asynq | `go test ./...` (Go not installed locally as of 2026-10-16) |
| PDF parser | `services/pdf-parser` | Python + FastAPI, pdfplumber | no tests yet |
| Web | `apps/web` | Next.js + TypeScript + Tailwind | `npm ci && npx next build` |
| DB schema | `infra/migrations` | Postgres SQL | `ExposedUserRepositoryIntegrationTest` (Testcontainers) |
| Run all | `docker-compose.yml` | Docker | `docker compose up` (currently broken, see PROGRESS) |
