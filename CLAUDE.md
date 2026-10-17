# AdminOS — context for Claude sessions

AdminOS is Pragun's **personal money console**. It collects transactions from bank
statements, manual entry, Gmail, SMS and WhatsApp, and shows where money goes each
day, week and month, plus his portfolio, net worth and policy-based investment guidance. It is
**always a personal, single-user project**, self-hosted and secured end to end (D-78); keep the
architecture clean and extensible, but build nothing for other users.

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
- Commit, push, open PRs and merge to `main` are allowed for this repo (Pragun, 2026-10-16), one PR per task.
  - Use `gh auth switch --hostname github.com --user pragunb02` first.
  - **Merge only after the security check has reported success**, using main's copy of the merge script: `f=$(mktemp) && git fetch origin && git show origin/main:scripts/merge-pr.sh > "$f" && bash "$f" N; rm -f "$f"` (a temp file, so a failed `git show` can't run an empty script; the merge happened only if it prints `MERGED #N`) (a PR can't change its own gate, D-101).
    - It waits for every check to complete, merges only if all are `success`, and otherwise exits without merging (including on a timeout).
    - Never hand-roll a wait loop: one that waited for "success" and then merged after a timeout let a failed check through (2026-10-16, PR #43).
- **Tickets (D-25):** Epic `AOS-E<n>` → Story `AOS-S<n>.<m>` → Task `AOS-<n><nn>`. New tickets copy `docs/tickets/_templates/`.
  - Before starting a task: create its file if missing, set it to 🟦 and add an Activity log entry.
  - When done: fill in Resolution, Actual and Done, tick the acceptance criteria, and update the story, the epic and the BACKLOG.md row.
- **Every change has a task ID** (add the ticket first if it's missing).
  - Use the ID in the branch (`aos-102-…`), the commit (`AOS-102: …`), PROGRESS entries and decisions.
- Each decision in DECISIONS.md states its **Type** (Architecture / Product / Scope / ETA / Process) and linked tasks, and is added to the index.
- Before ending a session, update `docs/PROGRESS.md`: what changed, what was verified, and the next step.
- Record any meaningful choice in `docs/DECISIONS.md` (append only).
- Code follows `docs/ENGINEERING.md`. A task is done only when its Definition of Done is met.
- **Security is P0 (D-91). Every feature rolls out only after the security gate (`docs/ENGINEERING.md` §4a, D-100):**
  - a threat review in the ticket;
  - unit, integration and Playwright attack tests;
  - no new public exposure.
  A security finding blocks the merge.
- **Every PR gets a code review and a security review before merge (`docs/ENGINEERING.md` §4b, D-101):**
  - two independent reviewer agents, each setting `review/code` / `review/security` on the commit it reviewed with `scripts/review-status.sh`;
  - GitHub branch protection and the merge script refuse to merge without both; a new push needs a fresh review.
- If you find a bug, report it and mention it in PROGRESS.md; don't silently fix things outside the agreed step.

## Stack
| Part | Path | Tech | Verify |
|---|---|---|---|
| API | `services/api` | Kotlin 2.4 + Ktor 3, Exposed 1.x, Flyway 13, JDK 25 | `./gradlew test`; `./gradlew integrationTest` (needs Docker/OrbStack) |
| Workers | `services/workers` | Go 1.27 + Asynq | Go isn't installed locally and `proxy.golang.org` is blocked, so run in Docker: `docker run --rm -e GOPROXY=direct -v "$PWD":/src -v aos-gomod:/go/pkg/mod -w /src golang:1.27 sh -c 'go build ./... && go vet ./... && go test ./...'` |
| Statement parser | `services/pdf-parser` | Python 3.14 + FastAPI, pdfplumber, openpyxl, xlrd | `docker run --rm -v "$PWD":/src -w /src python:3.14-slim sh -c 'pip install -q -r requirements-dev.txt && python -m pytest -q'` |
| SMS reader (Mac host) | `tools/sms-reader` | Go 1.27, pure-Go SQLite | same Docker Go command in `tools/sms-reader`; macOS binary: add `-e GOOS=darwin -e GOARCH=arm64 -e CGO_ENABLED=0` |
| Web | `apps/web` | Next 16 + React 19 + TS 6 + Tailwind 4 | `npm ci && npm run typecheck && npm run lint && npx next build` |
| DB schema | `services/api/src/main/resources/db/migration` (`V{N}__*.sql`, Flyway; never edit applied ones) | Postgres 18 | `./gradlew integrationTest` |
| Run all | `docker-compose.yml` | Docker | `docker compose up` (currently broken, see PROGRESS) |
