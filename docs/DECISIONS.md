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
