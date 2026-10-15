# Decision Log

## Index
**Types:** Architecture · Product · Scope · ETA · Process. Each row links the tasks it affects (see `BACKLOG.md`).

| ID | Type | Title | Status | Tasks |
|---|---|---|---|---|
| D-1 | Architecture | Polyglot stack | Accepted | — |
| D-2 | Architecture | On-device SMS parsing, self-hosted data | Accepted | AOS-203 |
| D-3 | Architecture | Claude for agent features | Accepted | AOS-506 |
| D-4 | Product | Personal spending console first | Accepted | AOS-3xx |
| D-5 | Architecture | Keep the multi-user architecture | Accepted | — |
| D-6 | Scope | Input priority v1 | Superseded by D-19 | — |
| D-7 | Scope | WhatsApp via export or notifications | Partly superseded by D-17 | — |
| D-8 | Architecture | Plain Postgres, no hypertable | Accepted | AOS-100, AOS-110 |
| D-9 | Architecture | Enum union | Accepted | AOS-100, AOS-101 |
| D-10 | Architecture | Arrays as TEXT | Accepted | AOS-100 |
| D-11 | Process | Announce before acting; update PROGRESS | Accepted | — |
| D-12 | Architecture | Flyway owns migrations | Proposed | AOS-102 |
| D-13 | Architecture | JDBC stringtype=unspecified | Proposed | AOS-103 |
| D-14 | Product | Google login for Milestone 1 | Accepted | AOS-106…112 |
| D-15 | Scope | No Account Aggregator for now | Accepted | — |
| D-16 | Architecture | iPhone SMS via Shortcuts | Accepted | AOS-203, AOS-404 |
| D-17 | Architecture | WhatsApp forward-to-bot + export; no Web automation | Accepted | AOS-402, AOS-403 |
| D-18 | Architecture | Gmail OAuth personal, forwarding address public | Accepted | AOS-401, AOS-603 |
| D-19 | Scope | Automatic capture first | Accepted | AOS-2xx |
| D-20 | Product | Accuracy engine is P0 | Accepted | AOS-205…210 |
| D-21 | Architecture | One common intake endpoint | Accepted | AOS-113, AOS-201 |
| D-22 | Scope | DPDP gate for public launch | Accepted | AOS-601, AOS-602 |
| D-23 | ETA | How ETAs are estimated and tracked | Accepted | all |
| D-24 | Process | Every change carries a task ID | Accepted | all |
| D-25 | Process | Amazon-style tickets: Epic → Story → Task in `docs/tickets/` | Accepted | AOS-004 |
| D-26 | Architecture | Five-layer architecture, coding rules, testing standards, Definition of Done (`docs/ENGINEERING.md`) | Accepted | AOS-006, AOS-007 |
| D-27 | Architecture | Design principles, pattern catalogue, design-before-code | Accepted | AOS-008 |
| D-28 | Architecture | Latest stable dependencies, exact pins, upgrade cadence | Accepted | AOS-009, S1.0 |
| D-29 | ETA | TypeScript 6.0.3 pin; set-state-in-effect at warn until AOS-308 | Accepted | AOS-116, AOS-308 |

Append new decisions at the bottom. Never edit an old decision. If it changes, add a new
entry that supersedes it.

Format: **ID — Title** · date · status (Accepted / Proposed / Superseded by D-x)

---

### D-1 — Polyglot architecture (Kotlin API, Go workers, Python PDF parser, Next.js web)
2026-04-03 · Accepted (original MVP)
- Kotlin + Ktor modular monolith for the API.
- Go + Asynq for background jobs.
- Python (pdfplumber) for statement parsing.
- Next.js + Tailwind for the web app.
- Postgres for data, Redis as the queue.

### D-2 — Privacy model: on-device SMS parsing, self-hosted data
2026-04-03 · Accepted
- Raw SMS text never leaves the phone; only structured JSON is sent to the API.
- All data lives in the user's own database.

### D-3 — Claude API for agent features
2026-04-04 · Accepted
- Claude writes the weekly briefings, explains unusual charges, and drafts cancellations.
- Categorization stays rule-based for now.

### D-4 — Re-scope: personal spending console first
2026-10-16 · Accepted
- The primary outcome is now dashboards showing where money goes (daily, monthly,
  category, merchant).
- Alerts and agent features move after the dashboards (Milestone 5).
- **Why:** I don't track spending today, and visibility is the first need.

### D-5 — Personal first, but keep the multi-user architecture
2026-10-16 · Accepted
- Keep the existing stack and the `user_id`-scoped data model.
- Don't simplify to a single-user script.
- **Why:** it has to be extensible to the general public later.

### D-6 — Ingest every source, in priority order
2026-10-16 · Accepted
- P0: statement upload and manual add.
- P1: Gmail.
- P2: Android SMS and WhatsApp.
- **Why:** statements are the most reliable and need no phone app.

### D-7 — WhatsApp via export or notification listener only
2026-10-16 · Accepted
- WhatsApp has no API for reading personal chats.
- Supported paths: import an exported chat `.txt`, or have the Android app capture
  WhatsApp notifications.

### D-8 — Drop the TimescaleDB hypertable; use plain Postgres
2026-10-16 · Accepted (change on branch `feat/personal-console`, not yet tested)
- `create_hypertable` failed: the primary key lacked `transacted_at`, and foreign keys
  pointed into the hypertable.
- **Why:** one person's data is small, and plain Postgres keeps the foreign keys.
- Can revisit at public scale.

### D-9 — Enum values: the SQL enum is the union of Kotlin and SQL values
2026-10-16 · Accepted (on branch, untested)
- The Kotlin enums and SQL enums had drifted apart.
- Both now contain every value, so neither inserts nor reads can fail.
- Clean up unused values later.

### D-10 — Array columns stored as comma-separated TEXT
2026-10-16 · Accepted (on branch, untested)
- Affects `oauth_scope`, `transaction_ids` and `bill_reminder_days`.
- **Why:** the Kotlin repositories already read and write comma-separated strings.

### D-11 — Working agreement with Claude sessions
2026-10-16 · Accepted
- Before any change, Claude states the goal and the next step and waits for approval.
- Read-only exploration is fine.
- Every session updates `docs/PROGRESS.md` before it ends.

---

## Proposed (awaiting approval)

### D-12 — Flyway owns migrations
Proposed
- Rename `001_*.sql` to `V1__*.sql` and move the files to `services/api/src/main/resources/db/migration`.
- Stop mounting them into Postgres `docker-entrypoint-initdb.d`.
- **Why:** two runners would conflict, and Flyway currently runs nothing.

### D-13 — JDBC `stringtype=unspecified`
Proposed
- Lets Postgres cast string parameters to enum, JSONB and INET.
- The Exposed table definitions map those columns as `varchar`/`text`.

### D-14 — Personal login for Milestone 1
2026-10-16 · **Accepted: Option A (Google login)**, chosen by Pragun
- Option A: fix Google login (cookie vs Bearer header, JSON field names).
- Option B: add a simple single-owner login for local use.
- Google remains the public login either way.

---

## Decisions from BRD v2 research (2026-10-16, approved by Pragun)

### D-15 — No Account Aggregator for now
2026-10-16 · Accepted
- Only RBI/SEBI/IRDAI/PFRDA-regulated entities can be FIUs (data receivers).
- Revisit for the public product through a regulated partner.

### D-16 — iPhone SMS via iOS Shortcuts automations
2026-10-16 · Accepted
- "Message contains X" automations run without confirmation and POST to our API.
- Optional backfill from the Mac `chat.db`.
- iOS SMS filter extensions are rejected: they can't read or forward messages.

### D-17 — WhatsApp via forward-to-bot and export import; never WhatsApp Web automation
2026-10-16 · Accepted
- The official Cloud API on a separate bot number receives forwarded messages.
- Exported `.txt` files are imported for backfill.
- whatsapp-web.js/Baileys are banned: they break the ToS and risk the user's number.
- Supersedes the notification-listener-only part of D-7; the Android listener remains a later public option.

### D-18 — Gmail: OAuth for personal use, forwarding address for the public
2026-10-16 · Accepted
- **Personal:** own GCP project, unverified Production app. It's exempt under 100 users and
  avoids the 7-day token expiry of Testing mode.
- **Public:** inbound forwarding address, avoiding the restricted-scope CASA assessment.

### D-19 — Input priority revised: automatic capture first
2026-10-16 · Accepted · Supersedes D-6
- P0: statement upload, iPhone Shortcuts SMS, cash quick-add.
- P1: Gmail.
- P2: WhatsApp, Android SMS.
- **Why:** manual-entry fatigue is the top reason people quit trackers, and I use an iPhone.

### D-20 — Accuracy engine is P0
2026-10-16 · Accepted
- Covers: dedup across sources, self-transfer detection, refund linking, merchant
  normalization, remembered category corrections, correct debit/credit.
- **Why:** wrong totals are the main complaint about existing apps (CRED Money).

### D-21 — One common intake endpoint; sources are adapters
2026-10-16 · Accepted
- Every source posts a normalized ingest event to the same endpoint.
- New sources don't touch the core.

### D-22 — DPDP compliance is a gate for public launch
2026-10-16 · Accepted
- Required: consent records, export/delete, encryption at rest (including tokens), access
  logs for 1 year, and the 72h breach process.
- Not needed for personal use.

---

### D-23 — ETA basis
2026-10-16 · Accepted · Type: ETA
- ETAs are effort estimates in focused hours, set when a task is created.
- Actual hours are recorded when the task is done. If actual exceeds ETA by more than 50%,
  note the reason in PROGRESS.md.
- Estimated effort: M1 ~18h, M2 ~31h, M3 ~19h. Milestones 1–3 together give a working
  personal console in ~68h.
- Calendar target dates are set once Pragun confirms weekly hours (AOS-005).
- Every new decision must state its type (Architecture / Product / Scope / ETA / Process).

### D-24 — Task IDs on everything
2026-10-16 · Accepted · Type: Process
- Every piece of work has an `AOS-xxx` ID in `BACKLOG.md`.
- Branch names (`aos-102-flyway-migrations`), commit messages (`AOS-102: …`), PROGRESS
  entries and decisions all reference it.
- If work isn't in the backlog, add it before starting.

### D-25 — Amazon-style tickets: Epic → Story → Task
2026-10-16 · Accepted · Type: Process · Tasks: AOS-004
- Tickets are markdown files in `docs/tickets/`, following the templates in `docs/tickets/_templates/`
  (epic.md, story.md, task.md).
- **Epic `AOS-E<n>`:** one per milestone. It is the parent ticket, holding goal, exit
  criteria, stories and ETA rollup.
- **Story `AOS-S<n>.<m>`:** a user-visible outcome ("As… I want… so that…"), with
  acceptance criteria and child tasks.
- **Task `AOS-<n><nn>`:** a unit of work with description, why, acceptance criteria,
  plan, verification, activity log and resolution.
- `docs/BACKLOG.md` is the board view (the tree with status).
- Task files are created when their story is picked up. Until then the task is a row in the story.
- **Why:** Pragun wants proper ticket tracking like at Amazon, kept in the repo so every
  Claude session can read it. GitHub Issues was considered and rejected for now.

### D-26 — Engineering standards: layered architecture, coding rules, tests, Definition of Done
2026-10-16 · Accepted · Type: Architecture · Tasks: AOS-006, AOS-007
- `docs/ENGINEERING.md` defines five layers (API → domain logic → domain data →
  persistence/dependencies) with three rules: adjacent layers only, calls go downward,
  request/response.
- Coding rules: typed values, no `!!`/null errors, fail fast with no silent fallbacks,
  immutability, small signatures, interfaces only with 2+ implementations, safe logging,
  wrapped errors.
- Testing standards and a Definition of Done.
- Applied to new and touched code; existing gaps are tracked through AOS-007, not a big-bang rewrite.
- **Why:** Pragun asked for good architectural practice and proper unit tests. Written as
  general engineering practice (the repo is public).

### D-27 — Design principles, pattern catalogue, design-before-code
2026-10-16 · Accepted · Type: Architecture · Tasks: AOS-008
- ENGINEERING.md §5–6 covers SOLID, DRY, KISS/YAGNI, composition, explicit config and privacy by design.
- Patterns mapped to our features: Adapter (sources), Strategy + Registry (bank parsers),
  Pipeline (accuracy engine), Specification (rules), Repository, Transactional Outbox,
  Domain events, Template Method, Mappers.
- Stories and non-trivial tasks fill in a **Design** section, approved by Pragun before coding.
- **Why:** the code must be future-extensible. New banks, sources and rules plug in without editing the core.

### D-28 — Dependencies: latest stable, pinned, regular upgrades
2026-10-16 · Accepted · Type: Architecture · Tasks: AOS-009, story AOS-S1.0
- Use only GA releases (no pre-releases), on the newest officially supported combination,
  with LTS runtimes.
- Pin exact versions.
- Check monthly; major upgrades get their own ticket and are done one service at a time.
- The 2026-10-16 audit found most core libraries 1+ majors behind (see AOS-009).

### D-29 — TypeScript 6.0.3 pin; React hooks rule at warn until the data layer exists
2026-10-16 · Accepted · Type: ETA · Tasks: AOS-116, AOS-308
- **TypeScript:** 7.0.2 is the latest stable, but typescript-eslint (used by eslint-config-next)
  supports `<6.1.0`. Per D-28 ("newest officially supported combination") we pin **6.0.3**.
  Revisit when typescript-eslint supports TS 7. Running TS 6 and 7 side by side was rejected
  as needless complexity.
- **Lint rule:** `react-hooks/set-state-in-effect` flags data fetching in 7 pages. Fixing it
  properly needs a shared data-fetching layer, an architecture choice that belongs with the
  Milestone 3 console work (AOS-308). Until then the rule is a **warning**, so the issues stay
  visible without blocking. **Why:** don't mix a version upgrade with a page-by-page refactor.
