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
| D-12 | Architecture | Flyway owns migrations | Accepted | AOS-102 |
| D-13 | Architecture | JDBC stringtype=unspecified | Accepted | AOS-103 |
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
| D-30 | Architecture | One JSON-column mapper (`JsonColumns`); tests must return Unit (guarded) | Accepted | AOS-105 |
| D-31 | Architecture | API contract: camelCase JSON and query params, opaque cursors, validated params | Accepted | AOS-107, AOS-120 |
| D-32 | Architecture | Same-origin proxy: web forwards /api/* to the API; edge proxy owns X-Forwarded-For | Accepted | AOS-108, AOS-605 |
| D-33 | Product | iPhone SMS raw text may reach the server for parsing, but is never stored or logged | Accepted | AOS-113, AOS-203 |
| D-34 | Architecture | Accuracy engine = Kotlin pipeline in the API; Go workers become fetchers | Accepted | AOS-201, AOS-205…210, AOS-401 |
| D-35 | Architecture | SMS transport: Mac Messages reader now; iOS Shortcut via Cloudflare Access once hosted | Accepted | AOS-203, AOS-404, AOS-605 |
| D-36 | Architecture | Device tokens: scoped JWT (typ=device) + revocable row; token types never interchangeable | Accepted | AOS-203 |
| D-37 | Architecture | Statements parsed by the Python service; files and passwords discarded after parsing | Accepted | AOS-202 |
| D-38 | Product | Cross-source dedup: reference first, then score with auto/suggest/separate bands; links, never overwrite | Accepted | AOS-207, AOS-307 |
| D-39 | Product | Transaction nature (spend/income/transfer/refund/reversal) drives all totals; card bill payments auto = transfer | Accepted | AOS-208, AOS-209, AOS-301 |
| D-40 | Business logic | Refund/reversal rules: reversal = exact amount ≤5 days with ref/narration; refund ≤ original (capped), same merchant, ≤90 days; refunds net category spend | Accepted | AOS-209 |
| D-41 | Business logic | One categorizer; corrections remembered per payee (UPI ID, else merchant), applied to future and past (not hand-set); no guessed transfers | Accepted | AOS-210 |
| D-42 | Business logic | ATM/cash withdrawals are transfers to cash; only cash logged by hand counts as spending | Accepted | AOS-204 |
| D-43 | Business logic | Email direction: account-anchored phrase wins, else one word family; unclear → needs-review, never guessed. Gmail writes ingest events, not transactions | Accepted | AOS-205, AOS-401 |
| D-44 | Architecture | One merchant catalogue as data (JSON), validated on load, shared by normalizer and categorizer; narration cleaning before title-case | Accepted | AOS-206 |
| D-45 | Architecture | Console plan: one analytics API (spent = spend − refunds, received = income, transfers excluded) in the user's timezone; Recharts; TanStack Query; review screen AOS-309 | Accepted | AOS-120, AOS-301…309 |
| D-46 | Business logic | Console totals are never clipped: total = Σ categories = Σ buckets; a refund-only day or category shows as negative | Accepted | AOS-301 |
| D-47 | Process | Web tests: Vitest + Testing Library + MSW (unit), Playwright (end-to-end); coverage gate 90% lines / 85% branches; console state (period, filters) lives in the URL | Accepted | AOS-310, AOS-311, AOS-312, AOS-303…309 |
| D-48 | Process | API coverage gate: Kover over the whole API (incl. legacy modules) at 90% lines / 85% branches; legacy MVP modules are kept as live code and brought to the bar | Accepted | AOS-312, AOS-608…613 |
| D-49 | Product | Console design: minimal and calm; 5 tabs (Home, Spending, Activity, Review, More); bars/lines only, no pies; undo over confirm; colour never the only signal; Indian number formatting | Accepted | AOS-302…309 |
| D-50 | Architecture | Scheduled jobs run in-process in the API; each (job, slot) is claimed in `job_runs` so it runs once across instances and restarts; bounded retries; a job is one registered class | Accepted | AOS-501 |
| D-56 | Architecture | Mandates (UPI AutoPay, e-NACH, card SI) are their own model, learnt from bank SMS notices (never the text), identified by UMN or payee+rail, robust to late/duplicate messages; debits matched after commit and marked recurring; dormant mandates flagged; shown on Subscriptions and the dues calendar | Accepted | AOS-502 |
| D-55 | Product | Dues calendar = bills by due date + renewals projected from next billing date (today on, ≤ 1 year, daily = next only, month-ends kept); renewal reminders on the bill-reminder days, once per date; every reminder and overdue judged on the user's own date | Accepted | AOS-503 |
| D-54 | Product | Bill paid = a spend or card-bill transfer of the full amount (±5%) or minimum due (±1%), in the bill's window (period end or 40 days before due → 15 days after), with evidence (card/account number, biller name, or the only card bill); ties match nothing; daily sweep for payments seen first | Accepted | AOS-508 |
| D-53 | Product | Unusual charges: every new spend is checked against its own past (30-day category average ≥3 spends, ₹2,000 minimum; 24 h duplicates; user-zone odd hours); one anomaly per transaction; alert only if confidence ≥ 0.7 and the spend is from the last 3 days | Accepted | AOS-505 |
| D-52 | Architecture | Web Push implemented from RFC 8291/8292 with JDK crypto + Nimbus (no Web Push library: outdated vulnerable deps); endpoints limited to known push services; PWA static files public | Accepted | AOS-510 |
| D-51 | Architecture | Notifications go out through pluggable channels (in-app, SMTP email, Web Push next); in-app is never held; push/email wait for quiet hours to end; bounded retries; at-least-once; Firebase dropped | Accepted | AOS-504, AOS-510 |

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

### D-13 (update) — JDBC `stringtype=unspecified` accepted
2026-10-16 · Accepted · Type: Architecture · Tasks: AOS-103
- Implemented and verified: integration tests go from 4/8 to 8/8.

### D-12 (update) — Flyway owns migrations: accepted
2026-10-16 · Accepted by Pragun · Type: Architecture · Tasks: AOS-102
- Implemented: `V{N}__*.sql` in the API's `db/migration`; no initdb mount.
- Startup fails fast on migration errors, with no `baselineOnMigrate`.
- New schema changes are always a new `V{n+1}__*.sql`. Never edit an applied migration.

### D-30 — One JSON-column mapper; test methods must return Unit
2026-10-16 · Accepted · Type: Architecture · Tasks: AOS-105
- **JSON columns:** every JSONB column holding `Map<String, Any?>` goes through
  `infrastructure/database/JsonColumns`.
  - Supported values: null, String, Boolean, numbers, maps, lists.
  - BigDecimal is written as an exact literal.
  - Other types throw.
  - **Why:** the copy-pasted serializer calls crashed at runtime in 6 repositories, and the
    Number path loses money precision.
- **Tests:** `@Test` bodies use `runBlocking<Unit> { }`. `TestConventionsTest` fails the build
  otherwise, because JUnit silently skips non-void test methods (9 tests were hidden).

### D-31 — API contract: camelCase, opaque cursors, validated query params
2026-10-16 · Accepted by Pragun · Type: Architecture · Tasks: AOS-107, AOS-120
- **camelCase** for JSON request/response fields **and** query parameters (e.g. `isRecurring`).
  - Pragun briefly chose snake_case on my recommendation. The audit showed the API (46 DTOs) and
    web (89 fields) already used camelCase with only 3 mismatches, so we switched.
  - External APIs keep their own names via `@SerialName` (e.g. Google's `access_token`).
- **Cursors** are opaque: `CursorCodec` (domain/common). Routes encode, and every repository
  implementation decodes the same way.
- **Query params** are parsed only via `infrastructure/plugins/QueryParams`. Invalid values are a
  400 `VALIDATION_003` naming the allowed values. Never a 500, never silently ignored.
- **Date-only ranges** are whole days in Asia/Kolkata until per-user timezones (AOS-120).

### D-32 — Same-origin proxy for the API
2026-10-16 · Accepted by Pragun · Type: Architecture · Tasks: AOS-108, AOS-605
- The browser talks only to the web origin. Next rewrites `/api/*` to the API (`API_INTERNAL_URL`).
  - Cookies are first-party, no CORS is needed, and the web guard sees the session cookie.
  - The same pattern works on real domains.
- Rejected: direct browser → API calls with CORS. They work on localhost only because cookies
  ignore ports; real domains would need shared-domain cookies.
- **Client IP:**
  - The API uses `XForwardedHeaders(useLastProxy)` and reads `call.clientIp`.
  - Next forwards client-supplied XFF unchanged, so a deployment **must** run an edge proxy that
    sets XFF from the real connection (AOS-605).
  - Locally everything is 127.0.0.1 behind localhost-bound ports.
- The web login guard never applies to `/api/*`; the API authenticates itself.

### D-33 — Raw SMS text from iPhone Shortcuts: parse on the server, never store
2026-10-16 · Accepted by Pragun (option 1) · Type: Product · Tasks: AOS-113, AOS-203 · Partly supersedes D-2
- **Why:** iOS Shortcuts can only forward the message text. It can't parse on-device, so strict D-2
  ("raw SMS never leaves the device") would rule out iPhone SMS capture entirely.
- **Rule:**
  - Raw text is accepted **only** by the SMS-parsing adapter (AOS-203). It is parsed in memory into
    a structured `IngestEvent`.
  - Only the structured fields and a hash (`sourceRef`) are kept. The raw text is **never written
    to the database, logs, queues, or error messages**.
  - Non-financial messages (OTPs, promos) are dropped unparsed.
- The common intake `/api/v1/ingest/events` accepts structured events only.
- D-2 still holds for the Android app (AOS-604): it parses on the device.

### D-34 — The accuracy engine is a Kotlin pipeline in the API; Go workers are fetchers
2026-10-16 · Accepted by Pragun (option A) · Type: Architecture · Tasks: AOS-201, AOS-205…210, AOS-401
- Intake events are processed by `IngestProcessor` → `IngestPipeline` (Chain of Responsibility) in
  the API.
  - New accuracy behaviour (dedup, self-transfer, refunds, user overrides) = a new `IngestStep`.
- **Why:**
  - The business rules already live in tested Kotlin.
  - The Go normalizer/categorizer duplicated them with bugs, against our single-source-of-truth rule.
  - Go suits concurrent I/O (fetching Gmail, PDFs).
- **Consequences:**
  - The Go SMS worker is retired (AOS-201).
  - Gmail/PDF workers will post `IngestEvent`s instead of writing transactions (AOS-401/202). The Go
    normalizer/categorizer are then deleted.

### D-35 — SMS transport: Mac Messages reader now, iOS Shortcut via Cloudflare later
2026-10-16 · Accepted by Pragun ("A now and C later") · Type: Architecture · Tasks: AOS-203, AOS-404, AOS-605
- **Now:**
  - The iPhone's Text Message Forwarding copies SMS to the Mac.
  - A small host-side reader (Go binary, Full Disk Access for that binary only) reads
    `~/Library/Messages/chat.db` read-only and keeps business senders only.
  - It posts to `127.0.0.1/api/v1/ingest/sms/raw` with a device token.
  - **Why:** zero network exposure, and nothing is lost while the Mac sleeps (Messages syncs on wake).
- **Later (public):**
  - An iOS Shortcut posts to the hosted API through Cloudflare Tunnel + Access (service token).
  - Same endpoint, same token model.
  - Trade-offs accepted: Cloudflare sees the plaintext; SMS sent while the server is unreachable are lost.
- **Rejected for financial data:** ngrok-style tunnels, Tailscale Funnel, Wi-Fi binds, iCloud file relays.
  Research is in the AOS-203 ticket.

### D-36 — Device tokens
2026-10-16 · Accepted by Pragun · Type: Architecture · Tasks: AOS-203
- **Token format:** a JWT from the existing `JwtService` with `typ=device`, `jti` = `device_tokens` row id,
  `scope` (only `ingest:sms` today), 365-day expiry.
- **Verification:** the row is checked on every use (revocation, expiry, owner, scope). `last_used_at` is recorded.
- **Separation:**
  - Access tokens now carry `typ=access`, and **both verifiers check type and issuer**.
  - A device token can never act as a login, and vice versa.
  - The device provider reads the Authorization header only (no cookies).
- **Handling:** shown once at creation; list responses never include it; revocable from Settings.
- The raw-SMS endpoint decodes bodies itself, so errors never echo message text (D-33).

### D-37 — Statement parsing in the Python service; files discarded
2026-10-16 · Accepted by Pragun · Type: Architecture · Tasks: AOS-202
- **Where:** the `pdf-parser` service parses PDF/CSV/XLS/XLSX statements.
  - pdfplumber is the strongest PDF table extractor; Python handles spreadsheets easily.
  - It is stateless, has no published port, and is called by the API over the internal network.
- **What happens to the data:**
  - Statement files and PDF passwords are used **in memory only** and discarded after parsing.
  - Only the resulting transactions are kept; to re-parse, re-upload (duplicates are skipped).
  - **Why:** a statement is the full account history, and storing it creates risk for no current benefit.
- **How columns are found:** by role, using header synonyms, not per-bank code. Bank quirks extend the synonyms.
- **Accuracy check:** running-balance reconciliation flags mis-read columns.

### D-38 — Cross-source duplicate detection rules
2026-10-16 · Accepted by Pragun (after research) · Type: Product · Tasks: AOS-207, AOS-307
- Reference (RRN) match first.
- Then a points score with three bands (≥8 auto-merge, 5–7 suggest, <5 separate) and an ambiguity guard (best − runner-up ≥ 3).
- **Windows by payment type:**
  - card −1…+3 days; UPI ±1 day; NEFT ±2 days; SMS vs email ±10 min;
  - hard limit 7 days.
- **Auto-merge only on exact amounts.** Manual entries are only suggested.
- **Merges are links** (`transaction_links`): source rows stay immutable, blanks are filled but values never overwritten, and user rejections are remembered.
- **Why:** this combines GnuCash's scoring bands, Actual Budget's id-first, 1:1 and fill-blanks approach, YNAB's windows and occurrence ids, and beangulp/Plaid's cautions on amount drift.
- Thresholds are versioned (`dedup-v1`) and will be tuned on real data.

### D-39 — Transaction nature drives the totals
2026-10-16 · Accepted by Pragun · Type: Product · Tasks: AOS-208, AOS-209, AOS-301
- Every transaction has a `nature`:
  - **spend** and **income** count normally;
  - **transfer** (between your own accounts, incl. paying your own card bill) counts as neither;
  - **refund** reduces the original's spending rather than counting as income;
  - **reversal** pairs net to zero.
- Direction (debit/credit) stays as-is; nature is separate.
- **Card bill payments are auto-marked as transfers** by high-precision narration patterns. Otherwise card spending counts twice.
- Relations are links with `relation` + state (merged / suggested / rejected), using the same auto/suggest bands as D-38, one review API.
- A category the user set is never overwritten.

### D-40 — Refund and reversal rules
2026-10-16 · Accepted by Pragun (combined AOS-208/209 design) · Type: Business logic · Tasks: AOS-209
- **Reversal** (a failed or bounced payment): exact amount, same or unknown account, credit within 5 days. Both sides net to zero.
  - It's auto-linked when the payment reference matches or the narration says reversal/failed. Otherwise a same-amount credit is only suggested.
- **Refund:** never more than the original, counting earlier refunds. It needs the same merchant (VPA or name tokens) and must come within 90 days.
  - It's auto-linked with a refund narration; partial refunds without one are not linked.
- A refund **reduces its category's spending** instead of counting as income.
- Same bands, ambiguity margin and review API as D-38. A user-set category is never overwritten.

### D-41 — One categorizer; corrections are remembered per payee
2026-10-16 · Accepted by Pragun ("agree") · Type: Business logic · Tasks: AOS-210
- **One categorizer** (Kotlin `CategorizationService`) for every source in the pipeline. The Go one goes with AOS-401.
- **Order:**
  1. your remembered rule;
  2. the built-in merchant list;
  3. whole-word patterns;
  4. Other.
- **A correction becomes the payee's rule:**
  - keyed by UPI ID when known (precise), otherwise by the normalized merchant name; the UPI rule wins;
  - a later correction replaces it;
  - `remember: false` changes only that one transaction.
- **It applies to the past too:** spending and refunds from that payee are re-labelled (`categorySource = remembered`).
  - Hand-set categories, transfers and reversals are not touched.
- **The categorizer never guesses "Transfer".** Unknown UPI payees, NEFT/IMPS narrations and wallet names (Paytm/PhonePe/GPay) are Other.
  - Real own-account transfers come from TransferStep (D-39). Guessing hid real spending.

### D-42 — ATM withdrawals are transfers to cash
2026-10-16 · Accepted by Pragun ("agree") · Type: Business logic · Tasks: AOS-204
- An ATM or cash withdrawal (narration `ATM`, `ATW-`, `NWD-`, `CASH WDL`, "withdrawn at ATM") is a **transfer**: money moved from the bank to your pocket.
- The **spending is the cash you log** (cash quick-add), with its own category. Otherwise every logged cash payment would count twice.
- **Trade-off:** cash withdrawn but never logged doesn't show as spending. A later "unlogged cash" view can show withdrawals minus logged cash (AOS-3xx).
- Cash entries keep the category you chose, are idempotent per entry, and are never matched as duplicates of bank transactions.

### D-43 — How an email's direction is decided; Gmail uses the common intake
2026-10-16 · Accepted by Pragun ("ok") · Type: Business logic · Tasks: AOS-205, AOS-401
- **Direction:**
  1. What happened to *your* account wins: "debited from / credited to (your) a/c|account|card".
     - Bank emails often say both: "debited from your a/c … credited to VPA x".
  2. Otherwise, if only debit words (debited, spent, paid, charged, purchase, withdrawn, sent) or only credit words (credited, received, refund, deposited, cashback, reversed) appear, that's the direction.
  3. **Otherwise it's unclear:** the email is skipped and counted as needs-review. Never guessed: a wrong "debit" on a salary email is worse than a gap.
- **The Gmail worker only fetches and extracts.** It writes `ingest_events` (source gmail, keyed by message ID) and never writes `transactions`. The pipeline does the rest (D-34).
- **The time** is the email's Date header; otherwise the date in the body at 00:00 India time.
- **If Gmail can't be reached, the job fails** (and is retried) instead of reporting a successful empty sync.

### D-44 — One merchant catalogue, as data; narrations are cleaned
2026-10-16 · Accepted by Pragun ("ok") · Type: Architecture · Tasks: AOS-206
- **Known merchants live in one data file,** `services/api/src/main/resources/merchants/catalogue.json`: brand, category, subcategory and aliases, including the legal names banks print.
  - The normalizer and the categorizer both read it.
  - Adding a brand is a data edit, not a code change.
- **Validated on load; a bad file fails startup.** Duplicate or cross-brand aliases, unknown categories and aliases under 3 characters are rejected; short aliases misfire.
- **Matching:**
  - whole words, in a form where punctuation is a space;
  - the longest alias wins;
  - the narration is checked first, then the UPI handle.
- **Unknown payees:** the name is pulled out of the narration structure (UPI/POS/gateway formats; references, IFSC codes, bank codes and channel words trimmed from the ends), then title-cased. If nothing is left, the UPI handle becomes the name.
- **Why:** consistent names are what the console groups by, and what remembered rules match on (D-41).

### D-45 — How the console is built
2026-10-16 · Accepted by Pragun ("yes yes"; chose Recharts, TanStack Query, add AOS-309) · Type: Architecture · Tasks: AOS-120, AOS-301…309
- **One analytics API** (AOS-301) defines the console's numbers, aggregated in SQL:
  - spent = spend − refunds;
  - received = income;
  - transfers and reversals count as neither (D-39).
  - Endpoints: summary with the previous period; a day/week/month series, zero-filled; categories; top merchants.
  - Amounts are exact decimal strings (D-30).
- **Days, weeks and months follow the user's timezone** (AOS-120). It travels in the access token (`tz` claim), so there is no lookup per request; a change applies at the next refresh (≤1h).
- **Web:**
  - Recharts for charts: SVG, React-first, covers bar/line/area/pie;
  - TanStack Query for data loading: cache, refetch, pagination, mutations with invalidation. It replaces per-page setState-in-effect (AOS-308).
- **AOS-309 added:** a review screen for suggested duplicates/transfers/refunds. Otherwise suggestions sit unseen and totals stay slightly off.
- **Order (one PR each):** AOS-120 → 301 → 302/308 → 303/304 → 305/306 → 307 → 309.

### D-46 — Console totals add up, with no clipping
2026-10-16 · Accepted (follows D-45, approved plan) · Type: Business logic · Tasks: AOS-301
- For any period, **spent = spend − refunds** and **received = income**, computed the same way for the total, each category and each day/week/month bucket.
- **Nothing is clipped,** so the total always equals the sum of the categories and the sum of the buckets.
  - A day or category with only a refund (its purchase was in an earlier period) shows as negative instead of disappearing.
  - The older `spendingByCategory` drops categories that net to ≤0; it is kept only for the old endpoint and the anomaly baseline.
- **The previous period** is the same number of days immediately before; the change is a % of the previous spent (none if that was 0).
- **Buckets:**
  - days are in the user's timezone;
  - weeks start on Monday;
  - edge buckets can be partial and are labelled by their start.

### D-47 — Testing bar for the web, and URL state
2026-10-16 · Accepted by Pragun ("yes yes") · Type: Process · Tasks: AOS-310, AOS-311, AOS-312, AOS-303…309
- **Unit/component tests:** Vitest + Testing Library + user-event, with network mocks in MSW.
  - An unmocked request fails the test.
  - Elements are found by role and accessible name.
- **End-to-end tests:** Playwright, in a real browser.
  - It runs against a mocked API (every branch, fast) and against the full stack (real API + Postgres).
  - For the full stack the test signs a session with the test secret; there is no auth bypass in production code.
  - Covers navigation chains (back → next → back → back), deep links, refresh, keyboard, accessibility (axe), and mobile + desktop.
- **Coverage gate:** 90% lines/statements and 85% branches/functions on the web. The same bar applies to the API through Kover (AOS-312).
- **The period and filters live in the URL** (e.g. `/?month=2026-10`, `/transactions?category=food`), so back/forward, refresh and sharing work.
  - Moving to another period adds a history entry; typing in search replaces the current one.
- **Design direction:** minimal and calm, easy to scroll and analyse (Pragun). The research is in the AOS-302 design spec.

### D-48 — API coverage gate over everything; legacy modules are kept
2026-10-16 · Accepted by Pragun ("Everything 90/85"; "whatever the legacy MVP module has that we do not have … let's bring that also") · Type: Process · Tasks: AOS-312, AOS-608…613
- **Kover measures the whole API** (unit + integration tests). `koverVerify` fails below **90% lines / 85% branches**.
- **The legacy MVP modules stay as live code** and are held to the same bar: agents/briefings, notifications/nudges, Gmail connect/webhook, crypto.
  - Their features are wired in through Epic E5 (AOS-501…506). The two gaps get new tickets: bill-payment matching and the subscription waste score.
- **A bug found by a test is never "fixed" by weakening the test.** It is committed `@Disabled("BUG …")` with a fix ticket; the fix enables it.

### D-49 — Console design
2026-10-16 · Accepted (Pragun: "very minimalistic and very convenient to scroll or do all sorts of analyses"; Plan 3 approved) · Type: Product · Tasks: AOS-302…309
- The full spec is `docs/design/console.md`, research-backed with sources.
- **Navigation:** exactly 5 destinations on phones (Home · Spending · Activity · Review · More), and a rail on desktop.
- **Home** answers one question: what I spent today, this week, this month, and on what.
- **Charts:** bars for comparisons and lines for change over time. No pies, donuts, stacked bars or gauges. Every chart has a text alternative and a table view.
- **Comparisons:** "so far" is compared with the *same days* of the previous period.
- **Undo instead of confirmation** for re-categorize, confirm and reject.
- **Colour is never the only signal:** refunds show "+" and a label; the palette is Okabe–Ito; contrast meets WCAG AA (enforced by axe, AOS-311).
- **Money:** Indian grouping (₹12,34,567); ₹1.2L / ₹1.2Cr on axes; amounts stay exact strings (D-30).

### D-50 — Scheduled jobs: in-process, claimed per slot
2026-10-16 · Accepted (Pragun: "go go ahead" on the E5 plan; "best practices … extensible … handle all the edge cases") · Type: Architecture · Tasks: AOS-501
- **Where:** jobs run inside the API, like the ingest processor (D-34). Rejected: Quartz (heavy), Asynq periodic tasks in Go (the jobs are Kotlin services), container cron hitting an admin endpoint (extra auth surface and container).
- **Once per slot:** a schedule divides time into slots; a run is started only by claiming (job, slot) in `job_runs` (unique key + row lock). Several API instances and restarts are safe.
- **Catch-up:** only the latest slot is considered, so after downtime a job runs once, not once per missed slot. Jobs must therefore be idempotent and read current state.
- **Failures:** retried after a delay (default 15 min, 3 attempts); a run whose instance died is taken over when its lease expires; a run over its timeout (default 10 min) is cancelled and recorded failed; shutdown records a failure so the slot is retried.
- **Extending:** a job is one class registered as `ScheduledJob`; a new schedule type is one `Schedule` implementation.
- **Off switch:** `SCHEDULER_ENABLED=false` for request-only instances and for the shared integration-test database.

### D-51 — Notification delivery through pluggable channels
2026-10-16 · Accepted (Pragun: "yes" to Web Push + SMTP email) · Type: Architecture · Tasks: AOS-504, AOS-510
- **Channels:** each is one `DeliveryChannel` class registered in Koin. An unconfigured channel isn't registered, so nothing is recorded as sent through it, and start-up logs what is off.
- **Providers:** email goes over plain SMTP, so any provider works (Gmail app password, SES SMTP, Mailgun). Push will be standard Web Push, which works on iPhone from the Home Screen app (D-19). Firebase (FCM) is dropped: it was a stub and the wrong channel for an iPhone and web user.
- **Quiet hours:** in-app is silent, so it is delivered at once. Push and email for non-critical types wait until quiet hours end in the user's zone. Anomaly alerts never wait.
- **Reliability:** delivery state is stored (`deliver_after`, `attempts`, `last_error`). Retries back off 5 min → 30 min → 3 h, then the notice is marked failed. A pending job claims due rows with a lease every 5 minutes. Delivery is at least once: a duplicate email beats a lost bill reminder.

### D-52 — Web Push from the standards, with JDK crypto
2026-10-16 · Accepted (Pragun: "yes buddy" to AOS-510; plan approved with D-51) · Type: Architecture · Tasks: AOS-510
- **No library:** the only Java Web Push library pulls BouncyCastle 1.70, jose4j 0.7.9 and async-http-client 2.12 (old, with known vulnerabilities). RFC 8291 (encryption) and RFC 8292 (VAPID) are implemented with JDK primitives (ECDH P-256, HKDF-SHA256, AES-128-GCM) and Nimbus (ES256 JWT, already a dependency), and verified against the RFC 8291 Appendix A test vector.
- **No SSRF:** the server posts only to Apple, Google, Mozilla and Microsoft push services (https, default port, no credentials in the URL).
- **Privacy:** endpoints identify a device, so they are never logged or returned; keys are never printed.
- **iPhone:** Web Push works for Home Screen apps (iOS 16.4+). The app has a manifest and icons, and Settings explains Add to Home Screen. The manifest, service worker and icons are served without a session, because browsers fetch the manifest without cookies.
- **Keys:** one VAPID pair per deployment (`./gradlew -q vapidKeys`). Rotating it means re-subscribing every device.

### D-53 — Unusual-charge alerts: when to check, when to tell
2026-10-16 · Accepted (Pragun: "continue" on the E5 plan) · Type: Product (+ Architecture for the hook) · Tasks: AOS-505
- **When:** after the pipeline stores a transaction, through a `TransactionCreatedListener` (Observer) outside the write transaction. A listener's failure never affects the event. Bill matching (AOS-508) will use the same hook.
- **What:** spends only. The context comes from the transaction's own past, so imported statements are judged fairly:
  - the 30-day same-category average needs at least 3 earlier spends, and the transaction must be at least ₹2,000;
  - a duplicate is the same amount at the same merchant within 24 h, the same minute included;
  - odd hours are 01:00–04:59 in the user's zone.
- **Once:** at most one anomaly per transaction, enforced by the V20 unique index.
- **Tell:** every anomaly is recorded and visible. Only those with confidence ≥ 0.7 on spends from the last 3 days send a critical alert, which ignores quiet hours (D-51). This avoids alert fatigue from late-night orders and from importing old statements.

### D-54 — When a payment pays a bill
2026-10-16 · Accepted (Pragun: "continue" on the E5 plan) · Type: Product · Tasks: AOS-508
- **Money that can pay a bill:** spends, and credit-card bill transfers (card bills are paid by transfer, AOS-208). Self-transfers, cash, refunds and income can't.
- **All three must hold:**
  - the amount is the full bill (±5%) or the minimum due (±1%);
  - the payment falls in the bill's window, from its period end (or 40 days before the due date) to 15 days after the due date, in Indian dates;
  - there is evidence it's this biller: the bill's last 4 digits in the payment (whole digits), a similar payee name, or — for a card-bill transfer — the only credit-card bill that fits.
- **Ranking:** stronger evidence, then the closer amount, then the nearer due date. An exact tie marks nothing paid, so nothing is guessed.
- **Both directions:** each new transaction is matched by a post-commit listener (D-53's hook), and the daily reminders job first sweeps open bills against payments already recorded, so a paid bill is never reminded.

### D-55 — Dues calendar and renewal reminders
2026-10-17 · Accepted (Pragun: "continue and go ahead") · Type: Product · Tasks: AOS-503
- **One month view** of bills (by due date) and expected renewals. Renewals are projected from the next billing date by cycle, from today on, at most a year ahead; daily subscriptions show only their next date; month-end renewals stay on month ends. Overdue bills from earlier months come with the current month.
- **Totals:** to pay (unpaid bills + earlier overdue), paid, expected renewals.
- **The user's calendar:** "today", "N days before" and "overdue" mean the user's own date, in the API and on the page (the page uses the API's `today`, not the browser clock).
- **Renewal reminders** use the bill-reminder setting and days. Each (renewal date, day) is sent once, stored with the subscription; the text says the date is expected from past payments.
- **Gmail bills** without a readable due date are skipped (counted), never failing a batch. Numeric dates are read day-first.

### D-56 — Mandates from bank SMS
2026-10-17 · Accepted (Pragun: "go go") · Type: Architecture · Tasks: AOS-502
- **A model of their own:** money pulled by UPI AutoPay, e-NACH or a card standing instruction isn't a subscription (loans, insurance and "as presented" utilities don't fit). It gets its own model, with its own status from the bank.
- **Source:** bank SMS notices for set-up, pre-debit, pause, resume and revoke. A pre-debit notice is a mandate notice, not a dropped "upcoming payment". Only the extracted fields are kept (D-33).
- **Identity:** the UMN/UMRN; otherwise the same payee on the same rail.
- **Robust to history:** the same message twice changes nothing; an older message never undoes a newer status; pre-debit dates only move forward.
- **Debits:** matched after the commit (D-53's hook), by the UMN, or by payee + amount within the limit + (the mandate marker or the expected date ±3 days). Ties match nothing. A matched debit marks its transaction recurring.
- **What the user sees:** status (including expired), the next debit, and dormant (nothing taken for two cycles: "revoke it in your UPI app or bank"). On the dues calendar a mandate replaces the same subscription's renewal. The UMN is never shown.

