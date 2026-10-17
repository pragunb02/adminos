# Decision Log

## Index
**Types:** Architecture · Product · Scope · ETA · Process. Each row links the tasks it affects (see `BACKLOG.md`).

| ID | Type | Title | Status | Tasks |
|---|---|---|---|---|
| D-93 | Process | Every route is authenticated unless on a reviewed allowlist, enforced by a route-inventory test over the real app; another user's resource is always 404 (same as missing); Playwright attack suite security.spec.ts; web guard matches public pages exactly | Accepted | AOS-619 |
| D-81 | Product | Habit lenses are data (quick commerce, food delivery, nights out, shopping, pay-later, travel) with per-merchant overrides; person-to-person UPI (settle-ups) never counts as a leak | Accepted | AOS-901 |
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
| D-59 | Architecture | Gmail sync starts from the stored history ID (never the push's), falls back to a 30-day filtered full sync, reads only financial senders, worker shares the API's TokenEncryptor v2 tokens, daily fallback with a 24 h push/manual skip rule | Accepted | AOS-401 |
| D-61 | Architecture | Categorization is measured on a synthetic, labelled golden set through the real pipeline steps; a regression gate fails any drop below `baseline.json` (overall, subcategory, per-category F1); raising the floor is a deliberate commit | Accepted | AOS-511 |
| D-62 | Architecture | AI fallback categorizer fills in "Other" only: off by default, rules always win, only business names (never people, UPI IDs, amounts or text) go to Claude Haiku via a strict JSON schema; confident (≥ 0.7) answers set the category as `ai`, unsure ones stay Other as a suggestion; batch job every 15 min with a per-merchant cache; gated by the golden set | Accepted | AOS-512 |
| D-63 | Architecture | Gmail push: the API registers `users.watch` after connect, renews every 6 h (missing/expired/ending within 24 h), `users.stop` on disconnect; INBOX only; failures recorded, never disconnect; narrow watch column writes; every push is an incremental sync | Accepted | AOS-405 |
| D-64 | Architecture | Connect Gmail: a random single-use OAuth state bound to user, purpose and redirect URI, stored server-side as a SHA-256 hash (10 min), checked before the code goes to Google; PKCE S256 with the verifier encrypted in the API | Accepted | AOS-406 |
| D-66 | Architecture | WhatsApp chat export import: only bank senders (by display name) are parsed, through the same SMS rules (`parseFromBank`); fingerprint of sender, time and text makes re-import idempotent; day/month order read from the file (default day first); user-zone times; text never kept (D-33) | Accepted | AOS-403 |
| D-65 | Product | Subscription detection looks back 400 days, uses the user's dates and calendar steps, takes the catalogue's category, and marks a subscription ended (reversibly) after two cycles without a charge; "cancel" gives honest steps (via the AutoPay mandate when one pays it), never a fake "queued" | Accepted | AOS-513 |
| D-60 | Architecture | Documents vault: user-chosen files stored in Cloudflare R2 (any S3), envelope-encrypted in the app (per-file AES-256-GCM key wrapped with the app key); owner-only access via the API; extends D-37 (imported statements are still never kept) | Accepted | AOS-507 |
| D-58 | Product | Subscription waste score in Kotlin from signals we have (price rise, duplicate service, cost share, long-running, dormant AutoPay, trial ending, marked unused); usage never guessed; ≥ 0.70 flags with reasons; "keep" remembers seen factors | Accepted | AOS-509 |
| D-57 | Product | Weekly briefing is deterministic: made in the API from the console's analytics and dues, written by a template (no LLM for numbers); on the user's day/time/zone, once per week, catch-up within the week; queue payloads keep JSON types | Accepted | AOS-506 |
| D-56 | Architecture | Mandates (UPI AutoPay, e-NACH, card SI) are their own model, learnt from bank SMS notices (never the text), identified by UMN or payee+rail, robust to late/duplicate messages; debits matched after commit and marked recurring; dormant mandates flagged; shown on Subscriptions and the dues calendar | Accepted | AOS-502 |
| D-55 | Product | Dues calendar = bills by due date + renewals projected from next billing date (today on, ≤ 1 year, daily = next only, month-ends kept); renewal reminders on the bill-reminder days, once per date; every reminder and overdue judged on the user's own date | Accepted | AOS-503 |
| D-54 | Product | Bill paid = a spend or card-bill transfer of the full amount (±5%) or minimum due (±1%), in the bill's window (period end or 40 days before due → 15 days after), with evidence (card/account number, biller name, or the only card bill); ties match nothing; daily sweep for payments seen first | Accepted | AOS-508 |
| D-53 | Product | Unusual charges: every new spend is checked against its own past (30-day category average ≥3 spends, ₹2,000 minimum; 24 h duplicates; user-zone odd hours); one anomaly per transaction; alert only if confidence ≥ 0.7 and the spend is from the last 3 days | Accepted | AOS-505 |
| D-52 | Architecture | Web Push implemented from RFC 8291/8292 with JDK crypto + Nimbus (no Web Push library: outdated vulnerable deps); endpoints limited to known push services; PWA static files public | Accepted | AOS-510 |
| D-51 | Architecture | Notifications go out through pluggable channels (in-app, SMTP email, Web Push next); in-app is never held; push/email wait for quiet hours to end; bounded retries; at-least-once; Firebase dropped | Accepted | AOS-504, AOS-510 |
| D-92 | Process / Architecture | Threat model (`docs/security/THREAT-MODEL.md`) is the reference for the security gate; audits use ASVS 5.0 L2 + WSTG with CVSS and a proof per finding; every request body is size-capped before routing (Content-Length required, any Transfer-Encoding refused, 1 MiB default, listed upload paths only); advisories fixed on discovery | Accepted | AOS-615 |
| D-98 | Architecture | Backups: nightly snapshot `pg_dump` piped into `age` (host holds only the public key), uploaded to a separate R2 bucket with bucket lock and lifecycle expiry; checksums + encrypted manifest; scripted monthly restore drill; keys offline | Accepted | AOS-621 |
| D-97 | Architecture | Private network only: reach AdminOS over Tailscale (tailnet HTTPS, owner devices only, Funnel off), no public listener; Gmail push off by default (daily fallback + Sync now), optionally a webhook-only Cloudflare Tunnel routing exactly `/api/v1/webhooks/gmail`; `APP_URL` must be https except loopback; a script fails any non-loopback publish | Accepted | AOS-616 |
| D-70 | Architecture / Product | Per-feature AI consent, off by default, stored per user and enforced in the AI gateway before redaction (no consent → nothing sent, nothing spent); Settings → AI features shows what each feature sends and never sends, local vs cloud, eval and spend; consent changes audited; same-origin guard on cookie mutations | Accepted | AOS-704 |
| D-69 | Architecture / Product | Local models: an opt-in Ollama compose profile with no published port (private `ai` network with the API only), measured by a bake-off through the AI port (rules floor, no rule row changed, ≥90% valid answers); qwen3:4b recommended (89.9% on the golden set), Claude Haiku stays the default | Accepted | AOS-703 |
| D-68 | Architecture | One AI gateway over a thin `AiProvider` port (Anthropic SDK, Ollama, fake); features are tasks with their own redactor and validator; flag → redaction → breaker → cost reservation → call → settle → validate → audit (no content); monthly cap fails closed; committed eval records gate start-up; legacy Go Claude path removed | Accepted | AOS-702 |
| D-101 | Process | Every PR gets two independent reviews (code, security) after its last push; each sets a commit status (`review/code`, `review/security`); `scripts/merge-pr.sh` merges only when both and every check pass on the head commit | Accepted | AOS-013 |
| D-100 | Process | Every feature rolls out only through the security gate (ENGINEERING §4a): threat review in the ticket, unit + integration + Playwright attack tests, no new exposure, dependency checks, attacker review before merge; findings block the merge | Accepted | AOS-012, all tasks |
| D-91 | Process / Architecture | Security is always P0: the site is owner-only (one allowlisted, pinned Google account, fail closed), private-network by default, defence in depth, and every security control has unit, integration and Playwright attack tests; we red-team our own site before go-live and on every security change | Accepted | AOS-614…623, S6.4 |
| D-80 | Product | North star "spend less → save more → invest well", measured by the savings rate; Epic E9 Spending coach (leaks, limits, nudges, goals) goes before the E8 wealth build; the coach informs and never acts; nudges are few and configurable | Accepted | AOS-011, E9 |
| D-78 | Scope | AdminOS is always personal and single-user, self-hosted and secured end to end; wealth (all holdings, net worth, policy-based guidance with AI explanations) is in scope; public-user work (E6 public ingestion, onboarding, DPDP consent records) is parked | Accepted | AOS-010, AOS-801, E6, E8 |
| D-67 | Architecture / Product | AI ground rules: AI never invents a number shown as fact; outputs validated against input or DB; each feature off by default with minimum data; local-first where good enough; one provider port; per-feature eval gate | Accepted | AOS-701, E7 (AOS-702…719) |

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

### D-57 — The weekly briefing states facts, not prose from a model
2026-10-17 · Accepted (Pragun: "ok"; concern about AI hallucination, 2026-10-16) · Type: Product · Tasks: AOS-506
- **Source:** the briefing is made in the API from `AnalyticsService` and `DuesService` (the same numbers as the console) plus open anomalies, flagged subscriptions and dormant mandates. A template writes it, so every number is a fact.
- **When:** the user's briefing day and time in their zone, about the 7 days before; catch-up within the week; one per (user, week); none for a week before sign-up.
- **AI later:** an LLM may only rephrase the template's facts, after an evaluation harness exists (proposed AOS-511). The legacy Go + Claude handler is kept but not used.
- **Queue:** payloads sent to the Go workers keep their JSON types, so typed Go structs can read them.

### D-58 — Subscription waste score in Kotlin, from signals we have
2026-10-17 · Accepted (Pragun: "can we do 2-3 task parallely" — built by a background agent, reviewed and merged by Claude) · Type: Product · Tasks: AOS-509

**Context:** The Go `ComputeWasteScore` scored only from Gmail login signals that nothing produces, and was never enqueued. The owner's data is iPhone SMS, bank statements and mandates.

**Decision:**
- A pure Kotlin `WasteScorer` (API, `domain/financial`) scores each active or trial subscription 0.00–1.00 as the sum of named, explained factors (points out of 100, capped):
  - trial ending within 7 days: 40;
  - marked unused: 40;
  - dormant AutoPay for the same payee: 40;
  - duplicate service of the same kind: 35;
  - price rise: 30, or 40 if 20% or more;
  - 5% or more of the last 30 days' spending: 25;
  - 12 or more months unchanged: 15.
- Usage is never guessed: unknown usage is said plainly ("Usage unknown"), with 0 points.
- A score of 0.70 or more flags the subscription with the top three reasons; below that the flag is cleared.
- "Keep" remembers the factors the user saw. Only a new factor flags it again.
- The job `subscription-waste-score` runs daily at 05:00 (scheduler zone), after detection.
- V24 stores the reasons and the kept factors. The API returns `wasteScore` and `wasteReasons`, and the web shows "Why flagged".
- The Go handler stays but is superseded.

**Alternatives rejected:**
- Feeding the Go scorer via the queue: two copies of one rule, payloads that break on non-strings, and signals we don't have.
- Scoring on every read: unstable flags, and "kept" can't be remembered.

**Consequences:**
- New signals (Gmail logins after AOS-401) are new factors.
- The thresholds are constants, to be tuned on real data.
- Detection should set the category and end stopped subscriptions (follow-ups).

### D-60 — Documents vault: encrypted in the app, stored in Cloudflare R2
2026-10-17 · Accepted (Pragun: "Cloudflare R2", after options were laid out) · Type: Architecture · Tasks: AOS-507 · Extends D-37
- **What's stored:** only documents the user puts in the vault. D-37 still holds for imported statements, which are never kept.
- **Where:** Cloudflare R2 through the S3 API (one adapter, so any S3-compatible store works). The bucket is private, with public access off. The R2 token has Object Read & Write on that bucket only.
- **How:** envelope encryption in the app, with JDK crypto.
  - A fresh 256-bit key per file encrypts it with AES-256-GCM, with the document ID as associated data.
  - The key is wrapped with the app key (`TokenEncryptor`).
  - A SHA-256 checksum is checked on download.
  - R2 holds only ciphertext under `documents/<id>`.
- **Access:** only through the API, after sign-in, and only to the owner. Anyone else gets 404, and secrets are never returned.
- **Without storage settings:** dev mode keeps files in memory (logged loudly). With a database, uploads are refused (503) rather than stored somewhere unsafe.
- **Consequence:** the vault is as safe as `TOKEN_ENCRYPTION_KEY`, which must be backed up.

### D-59 — Gmail sync: start point, filters, shared token encryption, daily fallback
2026-10-17 · Accepted (built in parallel by a background agent at Pragun's request, reviewed and merged by Claude) · Type: Architecture · Tasks: AOS-401
- **Start point:** a sync always starts from the history ID **stored on the connection** (`users.history.list`, messages added). The push notification's history ID is informational, because it is the mailbox's new position.
  - After a sync the connection stores Gmail's real history ID: from `history.list`, or from `users.getProfile` read before a full sync starts.
  - It only moves forward (numeric compare), and only on a successful sync.
- **Fallback:** with no usable history ID (none, or not a Gmail number), or when Gmail answers 404 (expired), a full sync runs over the last 30 days, at most 500 messages, with the filtered search.
- **Only financial mail is read:** a data list of sender domains (incl. `bank.in`), addresses and subject phrases.
  - A full sync searches `newer_than:30d (from:(…) OR subject:(…))`.
  - An incremental sync reads only From/Subject of each new message, and fetches the full body only when they match. Spam, trash, drafts and chats are never read.
- **Body:** text/plain, else HTML stripped to text, max 64 KB. It is used in memory for extraction only; it is never stored, serialised or logged, and Gmail errors carry statuses, not bodies (D-33).
- **Tokens:** the worker reads and writes the API's `TokenEncryptor` v2 format: `v2:` + base64(iv‖ct‖tag), AES-256-GCM, AAD `user_connections:<id>:<field>`, key `TOKEN_ENCRYPTION_KEY`.
  - Plain-text legacy tokens are read once and re-encrypted; pre-AOS-608 ciphertext is refused.
  - A shared vector (key = SHA-256 of a public seed, computed at test time) is tested by both services.
  - The Gmail worker refuses to start without the key.
- **Connect:** Gmail is connected only with `gmail.readonly` granted and a refresh token. The expiry and scopes are stored, and the first full sync is queued.
- **Retries:** 429, 5xx and network errors are retried with exponential backoff (4 tries); 401/403/404 are not. A failed sync is recorded on the session and the connection with a short reason; a revoked grant puts the connection in `error`.
- **Daily fallback:** the `gmail-fallback-sync` scheduled job (06:00, D-50) queues a sync for every connected Gmail connection, unless a push or manual sync of it completed in the last 24 h.
  - The fallback's own syncs don't count, so a connection without push syncs daily.
  - An expired access token is never a reason to disconnect.
- **Rejected:**
  - starting from the notification's history ID;
  - date-window searches instead of history;
  - the API refreshing tokens for the worker (a new internal endpoint);
  - an Asynq periodic task for the fallback.

### D-65 — Subscription detection and honest cancelling
2026-10-17 · Accepted (Pragun: "yes yes all") · Type: Product · Tasks: AOS-513
- **Detection:**
  - a 400-day look-back, so yearly plans are found;
  - the user's own dates;
  - the next charge by the calendar;
  - the category from the merchant catalogue, then the charges, then Other.
- **Ending:** no charge for two cycles plus 7 days marks a subscription cancelled with a reason; a new charge reverses it.
- **Cancelling:** AdminOS can't cancel anything, so "how to cancel" gives real steps. When a mandate pays it, revoke the mandate first (the UPI app, bank e-mandates, or card standing instructions), then cancel with the service. Nothing pretends work was queued.

### D-66 — WhatsApp chat export import
2026-10-17 · Accepted (Pragun: "also start next task thinking and all") · Type: Architecture · Tasks: AOS-403
- **Input:** a WhatsApp "Export chat" file, `.txt` (Android) or `.zip` with `_chat.txt` (iPhone), up to 20 MB, with at most 10 MB of chat text (capped while reading, so a zip bomb never inflates fully).
- **Who is read:** only senders whose display name is a bank: known banks (HDFC, SBI, ICICI, Axis, Kotak) get their own parser; other names with bank/card/credit/finance get the generic one. People and groups are never parsed.
- **How:** each bank message goes through the SMS registry's rules via `parseFromBank(text, bankCode)`: OTP and failed first, then mandate notices, then drop rules, then the bank parser. One rule set for SMS and WhatsApp.
- **Dates:** day/month order is read from the file (a number over 12 decides), else day first. Times are in the user's zone; a message dated in the future is skipped with a reason.
- **Idempotent:** `sourceRef` = SHA-256 of sender, time and normalised text. Re-importing, or importing a longer export, adds only new messages. The same alert received by SMS is matched by the pipeline's cross-source dedup.
- **Privacy:** the chat is used in memory only; nothing about it is stored or logged (D-33).
- **Rejected:** parsing every sender (a friend's "I paid 500" would become a transaction); a separate WhatsApp rule set (drift); storing the file.

### D-63 — Gmail push: the API registers and renews the watch
2026-10-17 · Accepted (built by a background agent at Pragun's request, reviewed and merged by Claude) · Type: Architecture · Tasks: AOS-405 · Extends D-59
- **Who calls Gmail:** the API calls `users.watch` and `users.stop` itself, with its own token refresh. The Go worker stays the only Gmail *reader*.
  - Why: `users.stop` must run before disconnect wipes the tokens, and renewal is a scheduled job (D-50) whose outcome the scheduler records.
  - Tokens stay encrypted at rest: a refreshed access token is stored in the TokenEncryptor v2 format with the worker's context (D-59).
- **When:**
  - right after Gmail is connected, once the first sync is queued;
  - by `gmail-watch-renewal` every 6 h, for every connected Gmail connection whose watch is missing, expired or ends within 24 h;
  - `users.stop` on disconnect, best effort.
- **What Gmail publishes:** INBOX changes only (`labelIds:["INBOX"]`, include). Every sync still reads all messages added since the stored history ID.
- **Stored on the connection:** `watch_expires_at`, `watch_history_id` (informational), `watch_error`, `watch_checked_at`. Only these columns are written by watch updates, so they never clobber the worker's history ID.
- **Failures:**
  - a failed watch is recorded and retried by the next run; it never disconnects or fails the connect;
  - Google's `invalid_grant` puts the connection in `error` (as the worker does), which stops renewals;
  - a 401 from Gmail is retried once with a refreshed token.
- **Off switch:** without `GOOGLE_GMAIL_PUBSUB_TOPIC` nothing is called (logged once); a malformed topic stops start-up.
- **Push:** every verified push queues an `incremental` sync from the stored history ID; its completion is what lets the daily fallback skip (D-59).
- **Rejected:**
  - the worker calling `users.watch`/`stop` via new queue jobs (the tokens are gone by the time it would stop);
  - an Asynq periodic task for renewal;
  - a separate watches table;
  - watching all mailbox changes.

### D-64 — Connect Gmail: a server-side, single-use OAuth state, with PKCE
2026-10-17 · Accepted (built by a background agent at Pragun's request, reviewed and merged by Claude) · Type: Architecture · Tasks: AOS-406 · Extends D-59
- **State:** the API issues 32 random bytes (base64url) per consent, bound to the signed-in user, the purpose (`gmail_connect`) and the redirect URI, valid for 10 minutes, usable once.
  - Stored server-side as SHA-256 only (`oauth_states`), so nothing is signed and no secret is added.
  - Checked **before** the code is sent to Google. Missing, unknown or mismatched → `CONN_010`; expired → `CONN_011`; reused → `CONN_012` (all 400). A mismatch never uses up the owner's state.
  - Single use is one conditional update, safe across instances.
- **PKCE:** S256 on every Gmail consent. The verifier never leaves the API and is stored encrypted (TokenEncryptor, context `oauth_states:<id>:code_verifier`).
- **Callback:** `/connections/gmail/callback` (signed-in only) posts code + state once, then shows success at `?connected=1` so refresh/Back never re-post. Every failure is said in words with a way to start again.
- **Housekeeping:** rows are deleted a day after they expire (kept that long so an old link reads "expired").
- **Rejected:**
  - an HMAC-signed state (still needs storage for single use);
  - a double-submit cookie (single use depends on the browser);
  - no PKCE.

### D-67 — AI ground rules for every smart feature
2026-10-17 · Accepted (Pragun: "great do start all with best decision long term") · Type: Architecture / Product · Tasks: AOS-701, Epic E7 (AOS-702…719) · Builds on D-3, D-33, D-57, D-61, D-62
- **AI never invents numbers shown as fact.**
  - Money, counts, dates and totals shown to the user come from deterministic code (the pipeline, analytics, forecasts).
  - A model may extract a number that is present in its input, choose a query or tool, classify or rephrase, but it never computes a figure the app presents as true.
- **Outputs are validated.**
  - Every model output uses a JSON schema (structured outputs or strict tools) and is checked against its source:
    - extracted amount, last 4 digits and date must appear in the text;
    - evidence spans must be exact substrings;
    - every number in generated prose must be in the facts given;
    - every number in an "Ask" answer must come from a tool result.
  - A failed check rejects the output, logs the reason (IDs and counts only), and falls back to the rule or template, or leaves the item in Review. No silent fallback.
- **Off by default, minimum data.**
  - Each AI feature is its own purpose with its own toggle (consent) and notice line.
  - A per-feature redactor sends only the fields needed: account and card numbers become last 4 digits, and phone numbers, emails and people's names are masked or dropped.
  - Raw message text is still never stored (D-33). Identity documents (passport, Aadhaar, PAN) never go to a cloud model.
- **Local-first where feasible.**
  - Classical models run in the API (JVM, e.g. Tribuo) and OCR runs in the Python service.
  - Features that read raw messages, documents, receipts or voice default to a local model (Ollama).
  - A cloud model (Claude: `claude-haiku-5-5` for extraction, `claude-sonnet-5-5` for tool use, `claude-opus-5-5` only if an eval needs it) is opt-in per feature.
- **One provider port.**
  - All model calls go through one `AiProvider` port with adapters (Anthropic, Ollama, a test fake). Model IDs live in configuration.
  - The port enforces a monthly cost cap that fails closed, and writes an audit row per call: feature, provider, model, prompt version, tokens, cost, outcome, latency. Never content.
  - AI features get no outbound tools (no sending, paying or cancelling). "Ask" tools are read-only, and the server scopes them to the signed-in user.
- **Per-feature eval gate.**
  - Each feature has a synthetic golden set and a committed baseline (generalising D-61).
  - A feature can't be turned on unless its eval meets the baseline, and a model-assisted variant may never lower a rule's precision or change a row the rules decided.
- **Rejected:**
  - letting models compute totals or write SQL (text-to-SQL benchmark error rates; it would re-implement D-39/D-46);
  - a third-party LLM framework (dependency weight, harder to audit what's sent);
  - cloud by default (privacy, DPDP minimisation for public users).
- **Consequences:**
  - New AI features are an `AiFeature` entry, a redactor, a validator, an eval set and a baseline.
  - AOS-702 refactors AOS-512's call path into the port.
  - The legacy Go `internal/claude` client is retired or routed through it.
  - Evidence and options: `docs/research/AI-ML-RESEARCH.md`.

### D-61 — Categorization is measured on a synthetic golden set, with a no-regression floor
2026-10-17 · Accepted (built by a background agent at Pragun's request, reviewed and merged by Claude) · Type: Architecture · Tasks: AOS-511, AOS-512
- **What's measured:** the shipped pipeline steps that decide a category on their own (normalize → categorize → card-bill/ATM transfer rules), run on each golden row with no other transactions.
- **Golden set:** `services/api/src/test/resources/categorization/golden.csv`.
  - It is synthetic, with realistic Indian formats and no real personal data; the expected label is what a careful person would choose.
  - It grows by scrubbed, real-shaped rows from Pragun's own corrections (process in AOS-511). Raw messages are never added (D-33).
- **Scores:**
  - per-category precision, recall and F1; accuracy; subcategory accuracy;
  - coverage (share not Other), reported but not gated, because guessing more isn't being right;
  - confusion pairs and misses.
- **Gate:**
  - a unit test fails if accuracy, subcategory accuracy or any category's F1 drops below `baseline.json`, naming each metric that dropped;
  - improvements are printed, not failed;
  - the floor is raised only deliberately, with `./gradlew -q categorizationReport -PwriteBaseline` committed alongside the change that moved it.
- **Each categorizer variant has its own baseline** (e.g. rules + AI in AOS-512). A model-assisted variant must beat or match the rules baseline, and may never change a row the rules decided.
- **Rejected:**
  - evaluating `CategorizationService` alone on hand-normalized names (it misses normalizer bugs; one was found);
  - real transactions in the repo (personal data);
  - a ratchet that fails on unrecorded improvements (it breaks `main` when parallel improvements merge).
- **Consequence:** D-57's "evaluation harness" exists. Model output may be considered where it can be scored this way.

### D-62 — An AI fallback fills in "Other" only: off by default, rules first, business names only, measured
2026-10-17 · Accepted (built by a background agent at Pragun's request — "AI fallback for Other, off by default, privacy-limited"; reviewed and merged by Claude) · Type: Architecture · Tasks: AOS-512 · Builds on D-33, D-41, D-57, D-61
- **When:**
  - only for transactions the built-in rules left as Other (`categorySource = rules`), for spending and refunds;
  - never when a remembered rule or catalogue entry exists, or when a person or a transfer rule decided.
  - Rules and corrections always win; a later correction becomes a rule (D-41) and the AI is never asked about that payee again.
- **Switch:**
  - off unless `AI_CATEGORIZATION_ENABLED=true`;
  - then `ANTHROPIC_API_KEY` is required, or the app refuses to start;
  - the model is `claude-haiku-5-5` by default (`AI_CATEGORIZATION_MODEL`).
- **What is sent (D-33):**
  - the cleaned merchant name, the payment method and the allowed categories;
  - a name is sent only with positive evidence it is a business (card payment, gateway descriptor, registered company, business word in the name).
  - Never amounts, dates, account or card numbers, UPI IDs, raw text, identity, or a person's name.
- **What is accepted:**
  - structured JSON (category from the list, which excludes Transfer; confidence 0–1; short reason), validated again on receipt;
  - anything else is rejected, and the row stays Other.
- **Applying:**
  - confidence ≥ 0.7 sets the category with source `ai`;
  - below that, the row stays Other with the guess in `metadata.ai` as a suggestion;
  - confidence, reason and model are stored either way.
- **How it runs:**
  - a scheduled job every 15 minutes over the last 30 days, never inside ingestion;
  - one question per merchant (cache `ai_category_cache`, V28);
  - SDK retries with backoff for 429/5xx;
  - a circuit breaker pauses for 15 minutes after 3 failures in a row;
  - idempotent through `metadata.ai`.
- **Measured (D-61):**
  - rules + AI has its own baseline on the golden set, from recorded answers;
  - tests prove it never changes a rule-decided row and never sees a `p2p` row;
  - real answers are recorded deliberately with `-PrecordAi`.
- **Rejected:**
  - a pipeline step or a post-commit listener (they slow ingestion);
  - reusing link suggestions (they are pairwise);
  - hand-written HTTP (we would own retries and errors);
  - sending every Other payee (people's names).
- **Consequence:** fewer Other rows where the model is sure, with every AI label traceable. D-57 still bars model-written prose; this decision only allows a category chosen from a closed list.

### D-62 addendum — AOS-512 design choices confirmed
2026-10-17 · Accepted (Pragun: "great do start all with best decision long term") · Type: Product · Tasks: AOS-512
- The batch job every 15 minutes (never inside ingestion), unsure answers kept in `metadata.ai` as suggestions (shown by the AOS-708 Review queue), the business-names-only privacy gate, and the official Anthropic Java SDK are confirmed. AOS-702 moves the client behind the `AiProvider` port (D-67).

### D-78 — Always personal; wealth in scope
2026-10-17 · Accepted (Pragun: "this is always a personal project e2e and secured"; maintain "my portfolio … stocks … investments and net worth and guidance") · Type: Scope · Tasks: AOS-010, AOS-801, Epic E6, Epic E8 · Supersedes the "later open to the public" goal (BRD v2 §1, §3, M6)
- **Who:** one user, Pragun. Self-hosted, every request authenticated and scoped to him. Nothing is built for other users: no onboarding, no Android app, no public ingestion paths, no consent records for others (parked, not deleted).
- **Architecture stays clean:** sources, asset types, rules and AI providers remain adapters. `user_id` stays on every row (it scopes data and costs nothing).
- **Wealth is in scope (Epic E8, design AOS-801):**
  - all holdings: Indian MF and stocks, foreign stocks / RSU / ESPP (multi-currency), FD, PPF, EPF, NPS, bonds, gold, crypto, real estate, loans given;
  - net worth with history;
  - guidance level "policy + AI explanations": rules from Pragun's own investment policy, each with its working; an AI may explain, but numbers come from code and it never picks securities or gives tips (D-67). This is planning support for the owner, not regulated advice.
- **Security:** no broker or aggregator passwords stored; holdings encrypted at rest like the documents vault (D-60); encrypted backups.
- **India-based:** INR first, Indian banks; other currencies for foreign holdings. It is not a product for India or anywhere.
- **Still out of scope:** trading or placing orders, stock tips, splitting with friends, paying or cancelling on Pragun's behalf, Account Aggregator, WhatsApp Web automation.
- **Rejected:** keeping multi-user extensibility as a goal (adds work and attack surface for no user).

### D-80 — North star and the spending coach first
2026-10-17 · Accepted (Pragun: the portal should help him become rich, "starting with how my spending habits are, and then I should focus on my investment things") · Type: Product · Tasks: AOS-011, Epic E9 · Builds on D-57, D-67, D-78
- **North star:** spend less → save more → invest well. The **savings rate**, (income − spend) / income per month, is the headline metric that links spending and wealth.
- **Order:** Epic E9 Spending coach is built before the E8 wealth implementation (E8 research continues). Go-live with real data follows in a day or two.
- **Coach principles:**
  - it changes behaviour by reaching the owner (push), not by adding screens he must remember to open;
  - few nudges: one evening digest, one Sunday report card, each limit threshold once per period; all configurable;
  - every number is deterministic and links to its transactions (D-57, D-67); projections show their assumptions;
  - it informs and never acts (no blocking apps or payments).
- **Habit lenses** are data (editable groups of merchants and categories), so a new app or habit is one entry.
- **Rejected:** gamification beyond streaks (noise); AI-written nudges (D-67: numbers from code; a later option for wording only).

### D-91 — Security is always P0; owner-only, defence in depth, attack-tested
2026-10-17 · Accepted (Pragun: "nobody is able to access it except me … security is always a P0 … we should be hackers of our website") · Type: Process / Architecture · Tasks: AOS-614…623, story S6.4 · Builds on D-32, D-60, D-67, D-78
- **Priority:** security work outranks features. A security finding blocks go-live and merges until fixed or explicitly accepted by Pragun.
- **Owner-only:** exactly one Google account can sign in: `OWNER_EMAIL`, verified, pinned to its Google ID after the first sign-in. No sign-up path. Fail closed: no owner configured, nobody gets in; with a database, start-up stops (AOS-614).
- **Defence in depth** (each layer assumes the one before failed):
  1. **Network:** not on the public internet by default; reachable only over a private network (Tailscale), and the DB, Redis and services stay on loopback or the compose network (AOS-616).
  2. **Identity:** Google plus a passkey (WebAuthn) as a second factor (AOS-617).
  3. **Session:** short access tokens, HttpOnly/Secure/SameSite cookies, rotation with reuse detection (AOS-121), alerts on every new or refused sign-in, revoke-all (AOS-620).
  4. **Authorization:** every route authenticated unless allowlisted; every resource scoped to its owner, tested per route (AOS-619).
  5. **Data:** secrets never in the repo; encryption at rest for tokens, documents and holdings (D-60, D-79); encrypted backups (AOS-621).
  6. **Supply chain and runtime:** dependency and container scanning, non-root read-only containers (AOS-622); GitGuardian on every commit.
- **Attack tests, always:** each control ships with unit, integration and Playwright attack specs (a stranger's account, a forged or replayed token, another user's IDs, missing CSRF, oversized uploads, path tricks). A red-team audit (OWASP ASVS L2 and WSTG) runs before go-live (AOS-615, AOS-623) and again on every security-relevant change.
- **Honesty:** "unhackable" isn't a property anyone can promise. The aim is no reachable attack surface from the internet, and every layer tested as if the others had failed.
- **Rejected:** relying on Google's "Testing" consent mode as the control (sensitive-scope refresh tokens expire every 7 days); a public URL protected only by the app's login.

### D-100 — Every feature ships through the security gate
2026-10-17 · Accepted (Pragun: "if we roll out a feature we should roll it out under all security testing; this should be our ongoing practice") · Type: Process · Tasks: AOS-012, every task from now on · Builds on D-91
- **Rule:** no feature merges until it passes `docs/ENGINEERING.md` §4a. A security finding blocks the merge until it is fixed or Pragun explicitly accepts it.
- **The gate:**
  - a threat review in the ticket;
  - attack tests at three levels: unit with hostile input; route tests for auth, ownership (404 for others' data), CSRF and limits; Playwright for signed-out access, forged tokens, safe rendering and no secrets client-side;
  - checks: no secrets, PII or raw text; no new public listener; dependencies clean; sensitive data encrypted; AI rules (D-67);
  - the integrator re-reads the diff as an attacker and runs the full Playwright suite before merging.
- **Enforced by:** the PR template; the route-inventory test (AOS-619), so an unprotected new route fails CI; CI dependency and image scans (AOS-622); GitGuardian on every commit; `scripts/merge-pr.sh` (merges only when every check is success).
- **Applies to agents too:** every background agent's brief includes the gate, and PRs without it are sent back.

### D-92 — Threat model and audit method
2026-10-18 · Accepted (built by the red-team agent under D-91; reviewed and merged by Claude) · Type: Process / Architecture · Tasks: AOS-615
- `docs/security/THREAT-MODEL.md` is the reference for every security-relevant change; the security gate (D-100) reviews a change against its attackers T1–T6 and boundaries TB1–TB11.
- Audits use ASVS 5.0 L2 + WSTG, a CVSS 3.1 base score plus an environmental note, and a proof per finding (a failing-before test where possible). Findings are `AUD-nn` rows in a dated audit file; open ones stay general in the public repo until fixed.
- **Every request body is size-capped before routing:** `Content-Length` required and any `Transfer-Encoding` refused (a chunked body would bypass the declared size), 1 MiB default, larger limits only for explicitly listed upload paths.
- **Dependency advisories are fixed on discovery** even when the environmental risk is low; transitive libraries are pinned with a BOM when needed, and unused dependencies are removed.
- Rejected: scanner-only audits; relying on the order "auth before body" for safety.

### D-97 — Private network only
2026-10-18 · Accepted (built by the infra-security agent under D-91; reviewed and merged by Claude) · Type: Architecture · Tasks: AOS-616
Type: Architecture / Security · Tasks: AOS-616 · Builds on D-91, D-32, D-59
- AdminOS has **no public listener**. The owner reaches it over **Tailscale**: `tailscale serve` on 443
  (tailnet only), MagicDNS, HTTPS certificates, device approval, key expiry on user devices, Tailnet Lock,
  and the repo's deny-by-default policy (owner devices → `tag:adminos:443` only; no Funnel).
- On the Mac the Tailscale app serves `127.0.0.1:3000`; on a server, the `docker-compose.tailnet.yml` sidecar.
- Compose publishes only on `127.0.0.1`; `scripts/check-compose-exposure.sh` enforces it for every stack and profile.
- `APP_URL` must be https unless loopback, and a bare origin; the API refuses to start otherwise.
- **Gmail push is off at go-live**; the daily fallback and Sync now cover email (D-59). If push is needed,
  prefer Pub/Sub pull (new ticket); the webhook-only Cloudflare Tunnel overlay (one path, OIDC-verified,
  everything else 404 at the edge) is the opt-in alternative.
- **Rejected:** Cloudflare Tunnel + Access for the whole site (public login page; Cloudflare sees plaintext);
  hand-run WireGuard (inbound port, manual keys and certs); a public port with the app login (D-91).
- **Integration note:** the webhook tunnel sends `Content-Length` (`disableChunkedEncoding: true`) because the API refuses any `Transfer-Encoding` (D-92).

### D-101 — Code and security review on every PR
2026-10-18 · Accepted (Pragun: "add a review step (code and security reviewers) on every PR before merge") · Type: Process · Tasks: AOS-013 · Builds on D-91, D-100
- **Who:** two reviewer agents that did not write the change: code (correctness, layers, coding rules, tests) and security (the D-100 gate and the threat model). They use `/code-review` and `/security-review` with the ticket and ENGINEERING.md.
- **Verdict:** a PR comment with findings, and a commit status on the **reviewed** SHA set with `scripts/review-status.sh` (it refuses if the head has moved). Blocking: any correctness bug, any security finding, missing tests for an acceptance criterion, a broken layer rule.
- **Enforced:**
  - GitHub branch protection on `main` (admins included) requires GitGuardian and both review statuses on an up-to-date head.
  - `scripts/merge-pr.sh`, always run from main's copy, also requires every check run, takes the newest status per context and only if the owner's account created it, and pins the merge to the reviewed SHA.
  - A new push has no status, so it needs a fresh review.
  - The statuses come from the owner's own token: they record that the process ran; they are not cryptographic proof. Branch protection can't check who set a status, so merges go through the script (which does), never the web UI.
- **Rejected:** GitHub required approvals (the author can't approve their own PRs, and every PR comes from one account); a checklist-only review.

### D-68 — One AI gateway over a thin provider port; features are tasks; committed eval records gate start-up
2026-10-18 · Accepted (Pragun: "start all with best decision long term"; built by the AI-foundation agent, reviewed and merged by Claude) · Type: Architecture · Tasks: AOS-702, AOS-512, AOS-703, AOS-704 · Builds on D-61, D-62, D-67
- **Port:** `AiProvider.complete(AiRequest) → AiCompletion` is the only vendor seam: text plus a JSON schema in, text plus token usage out. Failures are values with a code, never exceptions.
  - `AnthropicProvider`: the official Java SDK, structured output, low effort, thinking off where the model allows it.
  - `OllamaProvider`: `/api/chat`, schema in `format`, temperature 0, `think: false`.
  - Tests use a fake.
- **Features are tasks:** each `AiTask<I, O>` names its `AiFeature` and prompt version, and owns its redactor (`prompt` → send or withhold) and validator (`read` → valid, or invalid with a code).
- **One gateway** (`AiGateway.run`) applies, in order:
  1. flag/route;
  2. redaction;
  3. a circuit breaker per provider (3 failures → 15 minutes);
  4. a cost reservation;
  5. the call;
  6. settling to the real cost;
  7. validation;
  8. an audit row per call.
  Steps 1–4 make no call and write no row.
- **Cost cap:**
  - `AI_MONTHLY_CAP_USD` (default 2.00) per UTC month, in micro-dollars (never `Double`);
  - each call reserves its worst case (input bounded by its UTF-8 bytes, plus `max_tokens`) under a row lock, then settles;
  - an unknown cost (a provider that throws) is charged its worst case;
  - local models cost 0;
  - a model with no known price refuses start-up.
- **Audit (`ai_calls`):** user, feature, provider, model, prompt version, tokens, cost, outcome (accepted/rejected/error), reason code (`[a-z0-9_]{1,48}`, so text can't fit), latency and time. No content.
- **Flags:** `AI_<FEATURE>_ENABLED` (off by default), `_PROVIDER` (`anthropic` by default, or `ollama`) and `_MODEL` (`claude-haiku-5-5` by default on Anthropic; required on Ollama).
  - Keys and URLs are needed only for the providers in use.
  - A malformed setup refuses to start.
- **Eval gate (generalises D-61):**
  - `src/main/resources/ai/evals.json` holds one record per (feature, provider, model, prompt version): metrics, floor, and where the answers came from (`recorded-synthetic` / `recorded-real` / `live`);
  - start-up refuses an enabled route without a passing record;
  - the categorisation record is written by `categorizationReport -PwriteBaseline` and re-derived by a unit test;
  - it adds `ruleDecidedUnchanged` (floor 1.0), so "never change a rule-decided row" is part of the gate.
- **Redaction guard:** `PiiMasker` is shared. Cards and accounts become their last 4 digits; mobiles, emails, VPAs, Aadhaar-like numbers and PANs are masked. A categorisation name containing PII is withheld.
- **Go:** the legacy `internal/claude` client and the briefing/anomaly-explain handlers were never enqueued, so they were removed along with `CLAUDE_*`. The API's port is the only path to a model.
- **Rejected:**
  - decorators per adapter (they need feature context, and their order becomes a hidden rule);
  - running evals at start-up (needs a network, costs calls);
  - a test-only gate (can't stop a deploy with a different model);
  - summing the audit log for the cap (racy across calls and instances);
  - a framework (D-67).
- **Consequence:**
  - a new AI feature is an `AiFeature` entry, one task class, an eval set and a record;
  - a new vendor is one adapter with a price table;
  - a model switch is configuration plus a committed record.
- **Integration addition:** `OLLAMA_BASE_URL` allows plain http only for loopback or a compose service name; anything else must be https (prompts carry financial text, D-91).

### D-69 — Local models: an opt-in Ollama profile on loopback, measured by a bake-off through the AI port; qwen3:4b recommended
2026-10-18 · Accepted (Pragun: "start all with best decision long term"; built by the AI-foundation agent, reviewed and merged by Claude) · Type: Architecture / Product · Tasks: AOS-703, AOS-702 · Builds on D-61, D-67, D-68
- **Runtime:**
  - Ollama (`ollama/ollama:0.40.6`, pinned by digest) as a compose service under the `ollama` profile, so nothing starts it by default;
  - models in the `ollama_models` volume;
  - **no published port** (it has no authentication and skips its Host check on 0.0.0.0, so even a loopback publish is open to DNS rebinding). It sits on a private `ai` network with only the API, with `cap_drop: ALL` and `no-new-privileges`; host bake-offs use the native Ollama app; `ComposeExposureTest` enforces this;
  - the API reaches it at `http://ollama:11434`.
- **Measurement:**
  - `./gradlew -q aiBakeOff -Pmodels=…` runs each model through the golden set **via the AI port** (same prompt, redaction, validation and audit as production);
  - it prints rules / rules + recorded Claude / rules + each model: accuracy, macro-F1, coverage, AI labels, valid/rejected/unavailable answers, agreement with Claude, latency, rule rows kept and gate.
- **Gate for a local model:**
  - the rules baseline (D-61: match or beat the rules);
  - no rule-decided row changed;
  - at least 90% schema-valid answers (so a model that rejects everything can't pass by "matching the rules").
  - `-PwriteEvalRecord` records passing models only (`answers: live`). Their answers are committed and replayed by a test, so records can't drift.
- **Recommended default local model: `qwen3:4b`.** On the golden set it scored 89.9% accuracy and 0.894 macro-F1 (rules 82.6% / 0.827; recorded Claude 91.7% / 0.913), with all answers valid, all rule rows kept and about 2–3 s per question on a busy CPU.
  - `gemma3:4b` (89.2%) is the alternative.
  - Models under 4B parameters failed a category floor.
- **Not changed:** categorisation still defaults to Claude Haiku when enabled. Switching to local is `AI_CATEGORIZATION_PROVIDER=ollama`, `AI_CATEGORIZATION_MODEL=qwen3:4b` (Pragun's call).
- **Rejected:**
  - a shell script calling Ollama directly (skips redaction and validation, drifts from the real prompt);
  - tests that need Ollama (they would break every build; a stub server is used instead);
  - holding local models to the rules + Claude floor (that floor comes from synthetic answers);
  - no published port (the host-side bake-off couldn't reach it).
- **Consequence:**
  - any feature with a golden set can be measured on local models with the same harness;
  - a model change is a bake-off, a committed record and a config change.

### D-70 — AI consent per feature: off by default, enforced in the AI port, with the notice kept next to the redactor
2026-10-17 · Accepted (built by the AI-foundation agent; reviewed and merged by Claude, D-101 as amended) · Type: Product / Architecture · Tasks: AOS-704, AOS-702, AOS-512, AOS-602 · Builds on D-22, D-67, D-68, D-100
- **Consent:**
  - one choice per (user, feature) in `ai_consents`; no row means **off**;
  - every change writes an `ai.consent` audit entry (before, after, feature) as DPDP evidence.
- **Enforced in the port:**
  - `AiGateway` asks an `AiConsentCheck` before redaction, the cap or any call; without consent the call is refused, with nothing sent and nothing spent;
  - features also check before using anything cached (the categoriser's per-merchant cache), and its job skips users without consent without reading their transactions;
  - offline tools on synthetic data pass an explicit allow-all.
- **Turning on** is allowed only for a feature the server runs, which already means its eval passed (D-68): otherwise 409. Turning off always works.
- **Notice:**
  - each `AiTask` carries its own notice (title, purpose, what is sent, what is never sent), next to its redactor;
  - Settings shows it with where the data goes (local server vs Anthropic's cloud), provider and model, how it was measured (synthetic recordings are labelled), this month's spend and the server cap.
- **API:**
  - `GET /api/v1/ai/features`;
  - `PATCH /api/v1/ai/features/{key}` `{enabled}`: 404 for an unknown key (not echoed), 409 when unavailable, 400 for a bad body.
- **Cross-site guard (new, reusable):** `SameOriginMutations` refuses cookie-authenticated mutations unless `Sec-Fetch-Site` is `same-origin`/`none` or `Origin` equals `APP_URL` (403 `AUTH_007`). `Authorization` requests pass. AOS-618 decides whether to apply it to every mutation.
- **Rejected:**
  - consent checked only in each feature (a new feature could forget);
  - a UI-only toggle (background jobs have no request);
  - notice text in the web (it would drift from the redactor);
  - columns on notification preferences (they mix purposes);
  - CSRF tokens now (AOS-618's call).
- **Consequence:**
  - the AI categoriser runs only for users who turn it on (Pragun must turn it on once);
  - every later AI feature gets consent, notice, the 409 rule, audit and spend for free, from its task.

### D-81 — Habit lenses are data, and settle-ups are never a leak
2026-10-17 · Accepted (built by a background agent; reviewed and merged by Claude, D-101 as amended) · Type: Architecture / Product · Tasks: AOS-901, Epic E9 · Builds on D-39, D-41, D-44, D-61, D-80
- **What a lens is:** a named group of spending (quick commerce, food delivery, nights out, shopping, pay-later, travel) defined in `resources/coach/lenses.json` by brands, categories, subcategories and whole-word keywords. Validated against the merchant catalogue at startup; a bad file refuses to start.
- **Match order:** only spend and refund count (D-39); the owner's removal wins, then the owner's addition; a person-to-person UPI payment never counts; otherwise any rule matching puts it in. A refund nets off the lens it belongs to. Lenses may overlap; totals across lenses use the union.
- **Person-to-person:** a UPI payee with a personal app handle or a mobile-number handle, not a catalogue brand and with no merchant hint in the handle. Bank handles are not judged (shops use them too). Rationale: splitting bills means the owner's real share comes from Splitwise later (AOS-911); counting the settle-up would double it.
- **Overrides** are per merchant (trimmed, lower case) and per lens, stored in `lens_overrides`.
- **Catalogue:** the brands lenses need are catalogue entries (D-44); the golden-set floor is not raised by this change.
- **Rejected:** lenses as categories (an Instamart order is groceries *and* quick commerce; it would also churn the golden set); lenses in code (a new app would need a release); counting every Other-category UPI payment as person-to-person (hides small bars and kiosks).
### D-98 — Backups: age on the host, separate locked R2 bucket, monthly drill
2026-10-18 · Accepted (built by the infra-security agent under D-91; reviewed and merged by Claude) · Type: Architecture · Tasks: AOS-621
Type: Architecture / Security · Tasks: AOS-621 · Builds on D-91, D-60
- Nightly `pg_dump` (custom format, one snapshot) piped into **age** for a public recipient; the secret key is
  offline (password manager + paper). Restic/Kopia and GnuPG rejected (key on host / large legacy surface).
- Stored in a **separate** R2 bucket: no public access; **bucket lock** 30 days (`daily/`) and 365 days
  (`monthly/`); lifecycle deletes one day after; backup token Object Read & Write for that bucket only
  (R2 has no write-only); restore token Object Read only, kept offline.
- SHA-256 signed into each upload, stored beside it and inside the encrypted manifest (row counts per table).
- **Restore drill** monthly and before go-live: `scripts/backup/restore-drill.sh` into a throwaway database;
  any checksum, decrypt, restore or count mismatch fails.
- `TOKEN_ENCRYPTION_KEY`, `JWT_SECRET`, the age key and the restore token are never in the repo or the bucket.

P26-10-18 · Accepted (built by a background agent; reviewed and merged by Claude, D-101 as amended) · Type: Process · Tasks: AOS-619
- Every API route needs `authenticate("auth-jwt")` unless it is on the allowlist in `RouteInventoryTest` (today: `/health`, `/auth/google`, `/auth/refresh`, `/auth/logout`, `/webhooks/gmail`; device-token route `/ingest/sms/raw`). Adding an entry is a security decision recorded in the ticket with its replacing control and attack tests.
- Another user's resource is always **404 with the same error as a missing ID**, never 403; each new `{id}` route adds an IDOR row in `IdorTest`.
- `e2e/security.spec.ts` is the shared Playwright attack suite; new pages are added to `PAGES`.
- Rejected: hand-maintained route lists; 403 for others' resources.
