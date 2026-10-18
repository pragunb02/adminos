# Backlog board

Hierarchy: **Epic** (milestone) → **Story** (user-visible outcome) → **Task** (unit of work). Tickets live in `docs/tickets/`; templates are in `docs/tickets/_templates/`.

**IDs:** Epic `AOS-E<n>` · Story `AOS-S<n>.<m>` · Task `AOS-<n><nn>` (the hundreds digit is the milestone). IDs are permanent; never reuse them.

**Status:** ⬜ Todo · 🟦 In progress · 🟨 Done, unverified · ✅ Done · ⛔ Blocked · ⏸ Parked · ❌ Dropped. **ETA** = focused hours (D-23).

_Board generated 2026-10-16. When a ticket changes, update the ticket file **and** its row here._

## 🟦 [AOS-E0](tickets/AOS-E0.md) — Planning & docs · P0 · ETA 8.5h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S0.1](tickets/AOS-S0.1.md)** | **Project documentation & backlog** | | 🟦 | 8.5h | |
| └ [AOS-001](tickets/AOS-001.md) | Audit codebase (API, workers, web, schema, business rules) | Claude | ✅ | 1h | 1h |
| └ [AOS-002](tickets/AOS-002.md) | Re-scope to personal console; write BRD, DECISIONS, PROGRESS, CLAUDE.md | Claude | ✅ | 1h | 1h |
| └ [AOS-003](tickets/AOS-003.md) | BRD v2 from research (money, email, WhatsApp, iPhone SMS) | Claude | ✅ | 1h | 1h |
| └ [AOS-004](tickets/AOS-004.md) | Backlog, Amazon-style tickets, templates, decision index | Claude | ✅ | 1h | 1h |
| └ [AOS-005](tickets/AOS-005.md) | Confirm weekly hours so ETAs become target dates | Pragun | ⬜ | — |  |
| └ [AOS-006](tickets/AOS-006.md) | Engineering standards doc (architecture, coding, tests, DoD) | Claude | ✅ | 1h | 1h |
| └ [AOS-007](tickets/AOS-007.md) | Audit existing code against ENGINEERING.md (read-only) and file tickets | Claude | ⬜ | 2h |  |
| └ [AOS-008](tickets/AOS-008.md) | Design principles, patterns, design-before-code; dependency policy | Claude | ✅ | 1h | 1h |
| └ [AOS-009](tickets/AOS-009.md) | Dependency audit: current vs latest stable | Claude | ✅ | 0.5h | 0.5h |
| └ [AOS-010](tickets/AOS-010.md) | Re-scope: always personal and single-user; wealth (portfolio, net worth, guidance) in scope; public items parked (D-78) | Claude | ✅ | 0.5h | 0.5h |
| └ [AOS-011](tickets/AOS-011.md) | North star "spend less → save more → invest well"; Epic E9 Spending coach planned (D-80) | Claude | ✅ | 0.5h | 0.5h |
| └ [AOS-012](tickets/AOS-012.md) | Security gate for every feature rollout: ENGINEERING §4a, PR checklist, CLAUDE.md (D-100) | Claude | ✅ | 0.5h | 0.5h |
| └ [AOS-013](tickets/AOS-013.md) | Code + security review on every PR: reviewer statuses, merge script enforces them (D-101) | Claude | ✅ | 1h | 1h |

## 🟦 [AOS-E1](tickets/AOS-E1.md) — Foundation runs · P0 · ETA 30h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S1.0](tickets/AOS-S1.0.md)** | **Current, stable dependencies** | | ✅ | 11h | 4.5h |
| └ [AOS-115](tickets/AOS-115.md) | API: Gradle 9, Kotlin 2.4, Ktor 3, Exposed 1.x, Koin 4, Flyway 13, … | Claude | ✅ | 5h | 2h |
| └ [AOS-116](tickets/AOS-116.md) | Web: Next 16, React 19, Tailwind 4, ESLint flat, TS 6.0.3, Node 24 | Claude | ✅ | 3h | 1h |
| └ [AOS-117](tickets/AOS-117.md) | PDF parser: Python 3.14 + libraries | Claude | ✅ | 1h | 0.5h |
| └ [AOS-118](tickets/AOS-118.md) | Workers: Go 1.27 + modules, fix the Dockerfile Go version | Claude | ✅ | 1h | 0.5h |
| └ [AOS-119](tickets/AOS-119.md) | Runtime images: Postgres 18.6, Redis 8.10, localhost-only ports (JDK 25 image → AOS-110) | Claude | ✅ | 1h | 0.5h |
| **[AOS-S1.1](tickets/AOS-S1.1.md)** | **Database starts cleanly** | | ✅ | 7.5h | |
| └ [AOS-100](tickets/AOS-100.md) | Schema fixes: enum union, drop hypertable, arrays → TEXT | Claude | 🟨 | 1h | 1h |
| └ [AOS-101](tickets/AOS-101.md) | Compile API; fix the NotificationService `when` if broken by AOS-100 | Claude | ✅ | 0.5h | 0.25h |
| └ [AOS-102](tickets/AOS-102.md) | Flyway owns migrations | Claude | ✅ | 1h | 0.75h |
| └ [AOS-103](tickets/AOS-103.md) | JDBC `stringtype=unspecified` | Claude | ✅ | 0.5h | 0.25h |
| └ [AOS-104](tickets/AOS-104.md) | Run DB integration test; fix failures | Claude | ✅ | 1.5h | 0h |
| └ [AOS-105](tickets/AOS-105.md) | Integration tests for all Exposed repositories | Claude | ✅ | 3h | 2h |
| **[AOS-S1.2](tickets/AOS-S1.2.md)** | **I can log in with Google** | | 🟦 | 7.25h | |
| └ [AOS-106](tickets/AOS-106.md) | Auth reads the JWT from the `access_token` cookie too | Claude | ✅ | 1h | 0.5h |
| └ [AOS-107](tickets/AOS-107.md) | JSON/query contract web ↔ API (camelCase, D-31) | Claude | ✅ | 2h | 1.5h |
| └ [AOS-108](tickets/AOS-108.md) | Web config: same-origin API proxy (D-32), Google client id, fixes | Claude | ✅ | 1h | 1.5h |
| └ [AOS-109](tickets/AOS-109.md) | Session refresh via cookie + logout (this device) | Claude | ✅ | 2h | 1h |
| └ [AOS-111](tickets/AOS-111.md) | Create Google OAuth client | Pragun | ⬜ | 0.25h |  |
| └ [AOS-112](tickets/AOS-112.md) | End-to-end login on laptop | Claude + Pragun | ⬜ | 1h |  |
| **[AOS-S1.3](tickets/AOS-S1.3.md)** | **Whole stack runs with docker compose** | | ✅ | 1.25h | |
| └ [AOS-110](tickets/AOS-110.md) | API Dockerfile (JDK 25, non-root, healthcheck); compose waits for API | Claude | ✅ | 1h | 0.75h |
| └ [AOS-114](tickets/AOS-114.md) | Install Go locally (optional since AOS-118: Docker command in CLAUDE.md) | Pragun | ⬜ | 0.25h |  |
| **[AOS-S1.4](tickets/AOS-S1.4.md)** | **Common intake endpoint** | | ✅ | 3h | |
| └ [AOS-113](tickets/AOS-113.md) | Common intake endpoint POST /ingest/events (V9 outbox, D-21/D-33) | Claude | ✅ | 3h | 1.5h |

## 🟦 [AOS-E2](tickets/AOS-E2.md) — Data in (no typing) · P0 · ETA 31h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S2.1](tickets/AOS-S2.1.md)** | **Reliable ingestion pipeline** | | ✅ | 3h | |
| └ [AOS-201](tickets/AOS-201.md) | Ingest pipeline: pending events → transactions (Kotlin, D-34); Go SMS worker retired | Claude | ✅ | 3h | 2.5h |
| **[AOS-S2.2](tickets/AOS-S2.2.md)** | **Import bank statements** | | 🟦 | 6h | |
| └ [AOS-202](tickets/AOS-202.md) | Statement upload: Python parser + API + Import page (D-37) | Claude | ✅ | 6h | 4h |
| └ [AOS-212](tickets/AOS-212.md) | Validate statement import on real statements (locally) | Pragun + Claude | ⬜ | 1h |  |
| **[AOS-S2.3](tickets/AOS-S2.3.md)** | **iPhone SMS auto-capture** | | 🟦 | 5h | |
| └ [AOS-203](tickets/AOS-203.md) | iPhone SMS: parsers, device tokens, Mac Messages reader, setup guide (D-35/36) | Claude | ✅ | 5h | 3.5h |
| └ [AOS-211](tickets/AOS-211.md) | Set up SMS on Pragun's devices; validate parsers on real SMS (locally) | Pragun + Claude | ⬜ | 1h |  |
| **[AOS-S2.4](tickets/AOS-S2.4.md)** | **Cash quick-add** | | ✅ | 2h | 2h |
| └ [AOS-204](tickets/AOS-204.md) | Cash quick-add (API + two-tap UI); ATM = transfer to cash (D-42) | Claude | ✅ | 2h | 2h |
| **[AOS-S2.5](tickets/AOS-S2.5.md)** | **Numbers I can trust** (code done; verify with real data, AOS-211/212) | | 🟨 | 15h | 12.5h |
| └ [AOS-205](tickets/AOS-205.md) | Correct debit/credit everywhere: Gmail into the pipeline (D-43) | Claude | ✅ | 1.5h | 1.5h |
| └ [AOS-206](tickets/AOS-206.md) | Merchant names: one catalogue (104 brands) + narration cleaning (D-44) | Claude | ✅ | 2h | 1.5h |
| └ [AOS-207](tickets/AOS-207.md) | Cross-source dedup: reference → score bands → merge/suggest, review API (D-38) | Claude | ✅ | 3h | 3.5h |
| └ [AOS-208](tickets/AOS-208.md) | Own-account transfers + card bill payments; transaction nature (D-39) | Claude | ✅ | 3h | 2h |
| └ [AOS-209](tickets/AOS-209.md) | Refund/reversal linking; refunds net category spend (D-40) | Claude | ✅ | 2h | 2h |
| └ [AOS-612](tickets/AOS-612.md) | Bills and dashboard correctness (paging, refunds, ₹0, user timezone) | Claude | ✅ | 1.5h | 1h |
| └ [AOS-613](tickets/AOS-613.md) | Notifications and Gmail connect correctness | Claude | ✅ | 1.5h | 1h |
| └ [AOS-210](tickets/AOS-210.md) | Single categorizer with remembered corrections (D-41) | Claude | ✅ | 4h | 2h |
| └ [AOS-511](tickets/AOS-511.md) | Categorization evaluation harness: synthetic golden set, report, no-regression gate (D-61) | Claude (agent) | ✅ | — | 3h |
| └ [AOS-213](tickets/AOS-213.md) | Promised credits (cashback/refund "will be credited") are not transactions (D-120) | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-214](tickets/AOS-214.md) | Dr/Cr alerts with Avl Bal are kept; pure balance alerts still dropped | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-215](tickets/AOS-215.md) | SMS direction from account phrases and Dr/Cr; conflicts are needs-review (D-119) | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-512](tickets/AOS-512.md) | AI fallback categorizer for "Other": off by default, business names only, confident answers only, V28 (D-62) | Claude (agent) | ✅ | — | 4.5h |

## 🟦 [AOS-E3](tickets/AOS-E3.md) — Console (core value) · P0 · ETA 24h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S3.1](tickets/AOS-S3.1.md)** | **Analytics API & design** | | ✅ | 8h | 12.5h |
| └ [AOS-301](tickets/AOS-301.md) | Analytics API: summary, series, categories, merchants (D-46) | Claude | ✅ | 4h | 2h |
| └ [AOS-302](tickets/AOS-302.md) | Design system: spec (`docs/design/console.md`), tokens, formatters, 5-tab nav, period in URL (D-49) | Claude + Pragun | ✅ | 4h | 2.5h |
| └ [AOS-308](tickets/AOS-308.md) | Web: shared data-fetching layer: TanStack Query (D-45) | Claude | ✅ | 3h | 1.5h |
| └ [AOS-120](tickets/AOS-120.md) | Per-user timezone for date-range params (tz in the access token) | Claude | ✅ | 1h | 1h |
| └ [AOS-310](tickets/AOS-310.md) | Web unit tests + coverage gate (156 tests; 99.1% lines, 97.3% branches) (D-47) | Claude | ✅ | 4h | 2.5h |
| └ [AOS-311](tickets/AOS-311.md) | Playwright end-to-end: 131 tests on desktop Chrome, iPhone Safari, Android Chrome + full stack (D-47) | Claude | ✅ | 5h | 2.5h |
| └ [AOS-312](tickets/AOS-312.md) | API coverage: 99.6% lines / 95.4% branches, gate 90/85; found 18 bugs (D-48) | Claude | ✅ | 10h | 4h |
| **[AOS-S3.2](tickets/AOS-S3.2.md)** | **Daily & monthly views** | | ✅ | 6h | 3h |
| └ [AOS-303](tickets/AOS-303.md) | Console Home: headline vs same days, week bars, categories, merchants, latest, bills, attention | Claude | ✅ | 3h | 2h |
| └ [AOS-304](tickets/AOS-304.md) | Month view: running total vs last month, day by day | Claude | ✅ | 3h | 1h |
| **[AOS-S3.3](tickets/AOS-S3.3.md)** | **Where my money goes** | | ✅ | 5h | 2.25h |
| └ [AOS-305](tickets/AOS-305.md) | Spending: categories, merchants, category detail (6-month trend) | Claude | ✅ | 3h | 1.5h |
| └ [AOS-306](tickets/AOS-306.md) | Trends: 12 months (bars/lines) and 12 weeks | Claude | ✅ | 2h | 0.75h |
| **[AOS-S3.4](tickets/AOS-S3.4.md)** | **Transaction feed** | | ✅ | 5h | 4.75h |
| └ [AOS-611](tickets/AOS-611.md) | Exact money everywhere (D-30): every API money field is a string; web `lib/money.ts` | Claude | ✅ | 1.5h | 1.5h |
| └ [AOS-307](tickets/AOS-307.md) | Activity: search, filters in URL, edit sheet, re-categorize + undo; feed paging fix | Claude | ✅ | 3h | 2h |
| └ [AOS-313](tickets/AOS-313.md) | Flaky Activity e2e: wait for the feed request | Claude | ✅ | 0.25h | 0.25h |
| └ [AOS-631](tickets/AOS-631.md) | Date-dependent tests: fixed clocks, regression tests around India midnight | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-309](tickets/AOS-309.md) | Review: confirm or reject duplicates / transfers / refunds, Undo before sending (D-45, D-49) | Claude | ✅ | 2h | 1.25h |

## 🟦 [AOS-E4](tickets/AOS-E4.md) — More sources · P1 · ETA 18h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S4.1](tickets/AOS-S4.1.md)** | **Gmail import** | | 🟦 | 6h | |
| └ [AOS-401](tickets/AOS-401.md) | Gmail incremental sync (real history ID), full body, sender filters, encrypted tokens, connect flow, daily fallback with skip rule (D-59) — live check needs AOS-111 | Claude (agent) | 🟨 | 5h | 4.5h |
| └ [AOS-405](tickets/AOS-405.md) | Gmail push: `users.watch` after connect, 6-hourly renewal job, `users.stop` on disconnect, V26 (D-63) — live check needs AOS-111 | Claude (agent) | 🟨 | 4h | 3h |
| └ [AOS-406](tickets/AOS-406.md) | Web Connect Gmail: callback page, Settings buttons, single-use OAuth state + PKCE, V27 (D-64) — live check needs AOS-111 | Claude (agent) | 🟨 | 5h | 3.5h |
| **[AOS-S4.2](tickets/AOS-S4.2.md)** | **WhatsApp capture** | | 🟦 | 9h | |
| └ AOS-402 | WhatsApp forward-to-bot (Cloud API) | Claude + Pragun | ⬜ | 6h |  |
| └ [AOS-403](tickets/AOS-403.md) | WhatsApp chat export import (`.txt` / `.zip`), bank senders only, same rules as SMS (D-66) | Claude | ✅ | 3h | 2h |
| **[AOS-S4.3](tickets/AOS-S4.3.md)** | **SMS history backfill** | | ⬜ | 3h | |
| └ AOS-404 | Mac chat.db SMS backfill (delivered in AOS-203: `--backfill-days`) | Claude | ✅ | 3h | 0h |

## 🟦 [AOS-E5](tickets/AOS-E5.md) — Money admin & smart extras · P1 · ETA 26h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S5.1](tickets/AOS-S5.1.md)** | **Scheduler & notifications** | | ✅ | 7h | 7.5h |
| └ [AOS-501](tickets/AOS-501.md) | Scheduler: once per slot across instances, retries, timeouts; reminders, nudges, subscription detection (D-50) | Claude | ✅ | 3h | 2h |
| └ [AOS-504](tickets/AOS-504.md) | Real notifications: channel plug-ins, SMTP email, quiet-hours hold, retries (D-51) | Claude | ✅ | 4h | 2.5h |
| └ [AOS-510](tickets/AOS-510.md) | Web Push for iPhone (Home Screen app), Android, desktop: RFC 8291/8292, service worker, Settings (D-52) | Claude | ✅ | — | 3h |
| **[AOS-S5.2](tickets/AOS-S5.2.md)** | **Recurring payments & dues** | | ✅ | 8h | 6.5h |
| └ [AOS-502](tickets/AOS-502.md) | Mandates from SMS: UPI AutoPay, e-NACH, card SI; debits matched and marked recurring; dormant flagged (D-56) | Claude | ✅ | 4h | 2.5h |
| └ [AOS-503](tickets/AOS-503.md) | Dues & renewals calendar, renewal reminders; fixes: reminder days stored, user-zone dates, Gmail bills without a due date (D-55) | Claude | ✅ | 4h | 2.5h |
| └ [AOS-513](tickets/AOS-513.md) | Subscription detection: yearly found, user dates, calendar steps, catalogue category, ends stopped ones; honest "how to cancel" (D-65) | Claude | ✅ | — | 1.5h |
| └ [AOS-508](tickets/AOS-508.md) | Bill-payment matching after each transaction + daily sweep, incl. card bills paid by transfer (D-54) | Claude | ✅ | — | 1.5h |
| **[AOS-S5.3](tickets/AOS-S5.3.md)** | **Alerts & weekly briefing** | | ✅ | 5h | 3h |
| └ [AOS-505](tickets/AOS-505.md) | Unusual-charge alerts: every new spend checked against its own past; alert if confident and recent (D-53) | Claude | ✅ | 2h | 1.5h |
| └ [AOS-506](tickets/AOS-506.md) | Weekly briefing from real data, deterministic text; queue payloads typed (D-57) | Claude | ✅ | 3h | 1.5h |
| └ [AOS-509](tickets/AOS-509.md) | Subscription waste score from signals we have, explained reasons, keep remembers (D-58) | Claude (agent) | ✅ | — | 2h |
| **[AOS-S5.4](tickets/AOS-S5.4.md)** | **Documents vault** | | ✅ | 6h | 3h |
| └ [AOS-507](tickets/AOS-507.md) | Documents vault: encrypted in the app, Cloudflare R2, expiry reminders (D-60) | Claude | ✅ | 6h | 3h |

## 🟦 [AOS-E6](tickets/AOS-E6.md) — Hardening and security (public items parked, D-78) · **P0** (D-91) · ETA 45h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S6.1](tickets/AOS-S6.1.md)** | **Security & DPDP compliance** | | ⬜ | 11h | |
| └ [AOS-121](tickets/AOS-121.md) | Refresh-token rotation with reuse detection | Claude | ✅ | 2h |  |
| └ AOS-601 | Encrypt tokens at rest; refuse default secrets; require Pub/Sub audience | Claude | ⬜ | 3h |  |
| └ AOS-602 | ~~DPDP: consent records, export/delete, access logs~~ parked (personal use, D-78); export and backup move to E8 security | Claude | ⏸ | 8h |  |
| **[AOS-S6.4](tickets/AOS-S6.4.md)** | **Owner-only fortress** (P0, D-91) | | 🟦 | 39.5h | 1.5h |
| └ [AOS-614](tickets/AOS-614.md) | Owner-only sign-in: one allowlisted Google email, pinned Google ID, fail closed (critical finding) | Claude | ✅ | 1.5h | 1.5h |
| └ [AOS-615](tickets/AOS-615.md) | Threat model + red-team audit (ASVS 5.0 L2 / WSTG): 27 findings; fixed pre-auth body exhaustion and Jackson advisories; rest ticketed (D-92) | Claude (agent) | ✅ | 6h | 4h |
| └ [AOS-616](tickets/AOS-616.md) | Private network only: Tailscale, webhook-only tunnel option for Gmail push, exposure check script (D-97) | Claude (agent) | ✅ | 3h | 3h |
| └ [AOS-617](tickets/AOS-617.md) | Passkey (WebAuthn) second factor after Google, recovery codes (D-96) | Claude (agent) | ✅ | — | — |
| └ [AOS-618](tickets/AOS-618.md) | Hardening: nonce CSP + headers, app-wide CSRF check, sign-in state + PKCE, lockout, strict JWT, XFF distrusted (D-94) | Claude (agent) | ✅ | — | — |
| └ [AOS-619](tickets/AOS-619.md) | Authorization attack tests: route inventory, IDOR 404s, Playwright attack suite; exact public paths (D-93) | Claude (agent) | ✅ | — | — |
| └ [AOS-620](tickets/AOS-620.md) | Sign-in alerts, Security page, sign out everywhere; refresh rotation with reuse detection (also AOS-121) (D-95) | Claude (agent) | ✅ | — | — |
| └ [AOS-621](tickets/AOS-621.md) | Encrypted backups: age-encrypted pg_dump to a locked R2 bucket, restore drill (D-98) | Claude (agent) | ✅ | 5h | 4h |
| └ [AOS-622](tickets/AOS-622.md) | Supply chain and runtime: CI on every PR, OSV/npm audit/govulncheck/Trivy/CodeQL, Dependabot, pinned and locked-down containers (D-99) | Claude (agent) | ✅ | 4h | 6h |
| └ [AOS-623](tickets/AOS-623.md) | Self pen-test before go-live: ZAP baseline + scripted probes + manual checklist; findings fixed or accepted (D-150, D-151) | Claude | ✅ | 4h | 4h |
| └ [AOS-643](tickets/AOS-643.md) | Web: TRACE and other unsupported methods → 500 (accepted until the edge filters methods, D-151) | Claude | ✅ | — | — |
| └ [AOS-644](tickets/AOS-644.md) | Web: 404 pages under /favicon.ico had no CSP (fixed) | Claude | ✅ | 0.25h | 0.25h |
| └ [AOS-638](tickets/AOS-638.md) | Bill dates follow the user's calendar on an injected clock; unused Redis config removed (D-123) | Claude (agent) | ✅ | 2h | 2h |
| └ [AOS-637](tickets/AOS-637.md) | Worker logs the database as host:port/database only (no user info, no pgx error text) | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-636](tickets/AOS-636.md) | Test fixtures use reserved, obviously fake personal data + guard test | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-642](tickets/AOS-642.md) | Web tests: 5 s async timeout and real timers after each test (findBy flake under load) | Claude | ✅ | 0.5h | 0.5h |
| └ [AOS-640](tickets/AOS-640.md) | Playwright in GitHub CI as the required `e2e` check (all projects, cached browsers, per-run secret, report on failure) | Claude (agent) | 🟨 | 1h | 1h |
| └ [AOS-630](tickets/AOS-630.md) | Gmail webhook: debounce pushes per connection; replay can't multiply syncs (AUD-27) (D-114) | Claude (agent) | ✅ | 1h | 1.5h |
| └ [AOS-629](tickets/AOS-629.md) | `ConnectionService` requires a `TokenEncryptor` (no plaintext path) (AUD-25) | Claude (agent) | ✅ | 0.5h | 0.5h |
| └ [AOS-628](tickets/AOS-628.md) | Logging hygiene: no query strings in request logs, placeholders, no SQL parameters in logs (AUD-24) | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-627](tickets/AOS-627.md) | Push: registering an endpoint owned by another user is refused, not moved (AUD-23) | Claude (agent) | ✅ | 0.5h | 0.5h |
| └ [AOS-626](tickets/AOS-626.md) | Statement parser: per-file time, page and row caps; hostile-file tests (AUD-22) (D-113) | Claude (agent) | ✅ | 2h | 1.5h |
| └ [AOS-625](tickets/AOS-625.md) | WhatsApp zip: cap total decompressed bytes and time across entries (AUD-20) | Claude (agent) | ✅ | 1h | 0.75h |
| └ [AOS-624](tickets/AOS-624.md) | Authenticate bank mail: qualify by sender only, require DMARC/aligned DKIM pass (AUD-11) (D-112) | Claude (agent) | ✅ | 3h | 2h |
| └ [AOS-634](tickets/AOS-634.md) | Redis requires a password; API and worker get it and refuse to start without it (D-103) | Claude (agent) | ✅ | 2h | 2h |
| └ [AOS-633](tickets/AOS-633.md) | Non-JSON bodies are 415, malformed JSON 400, fixed messages (never 500, never echoed) (D-102) | Claude (agent) | ✅ | 1.5h | 1.5h |
| └ [AOS-632](tickets/AOS-632.md) | Web image ships public/ (service worker, icons) + image check | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-641](tickets/AOS-641.md) | Merge script: required checks must pass; others may be neutral/skipped, never failing | Claude | ✅ | 0.5h | 0.5h |
| └ [AOS-635](tickets/AOS-635.md) | AI replies: size and depth checked before parsing; a validator exception or stack overflow rejects the answer (audited), other JVM errors propagate | Claude (agent) | ✅ | 1h | 1h |
| **[AOS-S6.2](tickets/AOS-S6.2.md)** | **Public ingestion paths** (parked, D-78) | | ⏸ | 26h | |
| └ AOS-603 | Gmail forwarding-address inbound | Claude | ⏸ | 6h |  |
| └ AOS-604 | Android app (SMS + notification listener) | Claude | ⏸ | 20h |  |
| **[AOS-S6.3](tickets/AOS-S6.3.md)** | **Launch** | | ⬜ | 8h | |
| └ [AOS-605](tickets/AOS-605.md) | Hosting for me (edge proxy owns XFF, D-32); onboarding for others parked (D-78) | Claude + Pragun | ⬜ | 8h |  |
| └ [AOS-607](tickets/AOS-607.md) | JWT key now uses the whole `JWT_SECRET` (found by AOS-311) | Claude | ✅ | 0.5h | 0.5h |
| └ [AOS-608](tickets/AOS-608.md) | Token encryption: whole 256-bit key, row-bound ciphertext; no public default secrets | Claude | ✅ | 1.5h | 1h |
| └ [AOS-609](tickets/AOS-609.md) | Gmail Pub/Sub webhook authentication (issuer, service account, fail closed) | Claude | ✅ | 1h | 0.75h |
| └ [AOS-610](tickets/AOS-610.md) | Login hardening: inactive users, OAuth form encoding, Secure cookies, session revoke | Claude | ✅ | 1.5h | 1h |
| └ AOS-606 | Dev-dependency advisories: ESLint chain (`eslint-config-next` → `braces`, high, dev-only); upgrade when a non-breaking fix exists | Claude | ⬜ | 0.5h |  |

## 🟦 [AOS-E7](tickets/AOS-E7.md) — Smart features (AI/ML) · P2 · ETA 115h

Research and roadmap: [`docs/research/AI-ML-RESEARCH.md`](research/AI-ML-RESEARCH.md). Ground rules: D-67 (accepted). Builds on AOS-511/512 (merged). Wave 1 started 2026-10-17 in four background agents (702–704, 707–708, 713/715/709, 714/718); wave 2 (705, 706, 710–712, 716, 717, 719) after AOS-702.

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S7.1](tickets/AOS-S7.1.md)** | **AI foundation** | | 🟦 | 19h | 3h |
| └ [AOS-701](tickets/AOS-701.md) | Research: where AI/ML helps AdminOS; Epic E7; AI ground rules (D-67) | Claude | ✅ | 3h | 3h |
| └ [AOS-702](tickets/AOS-702.md) | AI layer: one gateway over a provider port (Anthropic, Ollama, fake), redaction, cost cap, audit, flags, eval gate; AOS-512 on the port; Go Claude path removed (D-68) | Claude (agent) | ✅ | 10h | 5h |
| └ [AOS-703](tickets/AOS-703.md) | Local models: opt-in Ollama profile (loopback), bake-off through the AI port; qwen3:4b recommended (D-69) | Claude (agent) | ✅ | 3h | 3h |
| └ [AOS-704](tickets/AOS-704.md) | Per-feature AI consent (off by default, enforced in the gateway); Settings → AI features notice; same-origin guard (D-70) | Claude (agent) | ✅ | 3h | 3h |
| **[AOS-S7.2](tickets/AOS-S7.2.md)** | **Smarter data in** | | 🟦 | 30h | |
| └ [AOS-705](tickets/AOS-705.md) | Message-reading fallback (SMS/Gmail/WhatsApp, Gmail bills) with grounding checks | Claude | ✅ | 8h | 7h |
| └ [AOS-706](tickets/AOS-706.md) | Template learning from repeated message shapes, human-approved | Claude | ✅ | 8h | 4h |
| └ [AOS-707](tickets/AOS-707.md) | Local learned categoriser (TF-IDF + logistic regression, calibrated), nightly retrain | Claude (agent) | ✅ | 8h | 7h |
| **[AOS-S7.3](tickets/AOS-S7.3.md)** | **Ask your money** | | ✅ | 18h | 13h |
| └ [AOS-708](tickets/AOS-708.md) | Review queue with active learning; golden set grows from scrubbed corrections | Claude (agent) | ✅ | 6h | 5.5h |
| └ [AOS-712](tickets/AOS-712.md) | Ask eval set: questions, expected tool calls, seeded synthetic DB | Claude | ✅ | 4h | 4h |
| └ [AOS-710](tickets/AOS-710.md) | Ask API: read-only analytics tools, period resolver, number check, cap | Claude | ✅ | 10h | 5h |
| └ [AOS-711](tickets/AOS-711.md) | Ask web sheet: suggestions, answer, "How I got this", feedback | Claude | ✅ | 4h | 4h |
| **[AOS-S7.4](tickets/AOS-S7.4.md)** | **Smarter alerts and planning** | | 🟦 | 21h | |
| └ [AOS-721](tickets/AOS-721.md) | Forecast: ProjectedBillsComponent for monthly bills not yet arrived | Claude | ✅ | 3h | 3h |
| └ [AOS-722](tickets/AOS-722.md) | Confirm or correct the detected salary | Claude | ✅ | 3h | 4h |
| └ [AOS-714](tickets/AOS-714.md) | Month-end forecast + money left until salary | Claude (agent) | ✅ | 8h | 9h |
| └ [AOS-720](tickets/AOS-720.md) | Latest balance per account, for money left until salary | Claude (agent) | 🟦 | 4h | 7h |
| └ [AOS-713](tickets/AOS-713.md) | Robust anomaly baselines (median/MAD per merchant) + learning from dismissals | Claude (agent) | ✅ | 6h | 5h |
| └ [AOS-709](tickets/AOS-709.md) | Fuzzy merchant grouping for subscriptions, merges via Review | Claude (agent) | ✅ | 5h | 5h |
| └ [AOS-715](tickets/AOS-715.md) | Subscription price-rise alert | Claude (agent) | ✅ | 2h | 1.5h |
| **[AOS-S7.5](tickets/AOS-S7.5.md)** | **Convenience** | | 🟦 | 27h | |
| └ [AOS-716](tickets/AOS-716.md) | Briefing narration with a number check (falls back to the template) | Claude (agent) | 🟨 | 4h | 5h |
| └ [AOS-717](tickets/AOS-717.md) | Documents vault OCR + field proposals, local-first: Tesseract, grounded rules, optional local AI (D-173, D-174, D-175) | Claude (agent) | 🟨 | 10h | 12h |
| └ [AOS-718](tickets/AOS-718.md) | Cash quick-add from free text or dictation | Claude (agent) | ✅ | 5h | 6h |
| └ AOS-719 | Receipt photo → matched to the card/UPI debit | Claude | ⬜ | 8h |  |

## 🟦 [AOS-E9](tickets/AOS-E9.md) — Spending coach · P0 · ETA 47h

Step one of the north star "spend less → save more → invest well" (D-80). Priority over the E8 build.

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S9.1](tickets/AOS-S9.1.md)** | **Where it leaks** | | ✅ | 18h | |
| └ [AOS-901](tickets/AOS-901.md) | Habit lenses as data, settle-ups excluded, per-merchant overrides; +37 catalogue brands (D-81) | Claude (agent) | ✅ | — | — |
| └ [AOS-902](tickets/AOS-902.md) | Leaks API + "Where it leaks" page: spend, order count, average order, late-night share, trend vs last month, per lens and merchant | Claude | ✅ | 6h |  |
| └ [AOS-903](tickets/AOS-903.md) | Order-receipt emails (quick-commerce, food delivery, e-commerce): fees and item counts from Gmail, matched to the payment; hidden fees per app (D-88) | Claude (agent) | ✅ | 8h | 6h |
| └ [AOS-914](tickets/AOS-914.md) | Leaks, limits, goals and savings count only the owner's share of a split bill; settlements count nowhere (D-139) | Claude (agent) | ✅ | 2h | 2h |
| **[AOS-S9.2](tickets/AOS-S9.2.md)** | **Limits and nudges** | | ✅ | 12h | |
| └ [AOS-912](tickets/AOS-912.md) | Pay-later lens counts pay-later purchases (payment method), never provider repayments (D-81, D-89) | Claude (agent) | ✅ | 1h | 1h |
| └ [AOS-904](tickets/AOS-904.md) | Limits per lens, merchant or category (monthly/weekly), pushes at 50/80/100%, once per threshold per period | Claude | ✅ | 5h |  |
| └ [AOS-905](tickets/AOS-905.md) | Evening digest push: today's spend, top leaks, limit status (time configurable; silent on a zero day if wanted) | Claude | ✅ | 3h |  |
| └ [AOS-906](tickets/AOS-906.md) | Sunday report card: week vs usual, best and worst lens, one concrete suggestion (deterministic, D-57) | Claude | ✅ | 4h |  |
| **[AOS-S9.3](tickets/AOS-S9.3.md)** | **Goals and savings rate** | | ✅ | 9h | |
| └ [AOS-907](tickets/AOS-907.md) | Goals (cut a lens by X%, or keep under ₹Y) with weekly progress and no-spend-day streaks | Claude | ✅ | 5h |  |
| └ [AOS-908](tickets/AOS-908.md) | Savings rate as the headline metric (income − spend) / income, monthly trend; "what this could become": monthly leak as a future SIP value with explicit assumptions | Claude | ✅ | 4h |  |
| └ [AOS-911](tickets/AOS-911.md) | Splitwise import: count only my share of shared bills; settle-ups are neither spending nor income (D-111) | Claude (agent) | 🟦 | 8h | 8h |
| **[AOS-S9.4](tickets/AOS-S9.4.md)** | **Pay-later and trips** | | 🟦 | 8h | |
| └ [AOS-909](tickets/AOS-909.md) | Pay-later (BNPL) bills and dues from SMS/email into the dues calendar and reminders; repayments are transfers that pay the bill; gaps in Review (D-89) | Claude (agent) | ✅ | 3h | — |
| └ [AOS-910](tickets/AOS-910.md) | Trips: group travel bookings and the spends around them into one trip, with its total cost; editable; measured rule with a baseline (D-90) | Claude (agent) | ✅ | 5h | — |
| └ [AOS-911](tickets/AOS-911.md) | Splitwise import: count only my share of shared bills; settle-ups are neither spending nor income (D-111) | Claude (agent) | ✅ | 8h | 8h |

## 🟦 [AOS-E8](tickets/AOS-E8.md) — Wealth · P1 · ETA 128h

Research and roadmap: [`docs/research/WEALTH-RESEARCH.md`](research/WEALTH-RESEARCH.md). Scope: D-78 (wealth, personal only). Architecture and guardrails: D-79 (proposed in AOS-801). Built after E9 (D-80), except AOS-803 backups, which protect existing data too. Order: wave 1 803 → 802 → 805 → 810 → 811 → 812 → 820; wave 2 815, 814, 816, 807; wave 3 817 → 818 → 821; wave 4 806, 808, 813, 804, 819, 809.

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S8.1](tickets/AOS-S8.1.md)** | **Secure wealth foundation** | | 🟦 | 19h | 3h |
| └ [AOS-801](tickets/AOS-801.md) | Wealth research, Epic E8 and D-79 (D-79) | Claude (agent) | ✅ | — | — |
| └ AOS-802 | Wealth domain model + schema, exact quantity/price/FX types, encrypted identifiers + blind index | Claude | ⬜ | 8h |  |
| └ AOS-803 | Encrypted offsite backups (pg_dump → age → separate R2 bucket), restore drill, key escrow guide | Claude + Pragun | ⬜ | 5h |  |
| └ AOS-804 | Wealth audit events, re-auth for exports and reveals, privacy mode | Claude | ⬜ | 3h |  |
| **[AOS-S8.2](tickets/AOS-S8.2.md)** | **Holdings data in, without credentials** | | ⬜ | 33h | |
| └ AOS-805 | CAS import: CAMS/KFintech + NSDL/CDSL (casparser), unit reconciliation | Claude | ⬜ | 8h |  |
| └ AOS-806 | Broker exports: Zerodha Console, Groww | Claude | ⬜ | 5h |  |
| └ AOS-807 | Equity awards (RSU/ESPP: E*TRADE/MS at Work, Schwab, Fidelity) + IBKR Flex Query | Claude | ⬜ | 8h |  |
| └ AOS-808 | EPF, NPS, PPF/FD, gold/SGB, property, loans, crypto CSV, manual entries | Claude | ⬜ | 8h |  |
| └ AOS-809 | Opt-in CAS auto-fetch from Gmail attachments | Claude | ⬜ | 4h |  |
| **[AOS-S8.3](tickets/AOS-S8.3.md)** | **Prices, FX and calculations** | | ⬜ | 28h | |
| └ AOS-810 | Price and FX sources (AMFI, NPS, FBIL/ECB, SBI TTBR, US EOD, CoinGecko, bhavcopy upload), staleness | Claude | ⬜ | 8h |  |
| └ AOS-811 | Lots and gains engine: FIFO, corporate actions, multi-currency | Claude | ⬜ | 8h |  |
| └ AOS-812 | XIRR (bracketed solver) + TWR | Claude | ⬜ | 4h |  |
| └ AOS-813 | Tax reports per FY (gains schedule, Schedule FA sheet, LRS tracker) from a rules table | Claude | ⬜ | 8h |  |
| **[AOS-S8.4](tickets/AOS-S8.4.md)** | **Net worth** | | ⬜ | 15h | |
| └ AOS-814 | Balance observations + liabilities (card dues, loans) | Claude | ⬜ | 5h |  |
| └ AOS-815 | Investment flows in the bank feed: `INVESTMENT` nature, matching, no double counting | Claude | ⬜ | 6h |  |
| └ AOS-816 | Net worth snapshots (monthly job), versioning, backfill | Claude | ⬜ | 4h |  |
| **[AOS-S8.5](tickets/AOS-S8.5.md)** | **Policy-based guidance** | | ⬜ | 19h | |
| └ AOS-817 | Investment Policy Statement: structured, versioned | Claude + Pragun | ⬜ | 4h |  |
| └ AOS-818 | Guidance rules engine + golden set gate | Claude | ⬜ | 10h |  |
| └ AOS-819 | AI explanations under D-67 (facts only, number and content checks), off by default | Claude | ⬜ | 5h |  |
| **[AOS-S8.6](tickets/AOS-S8.6.md)** | **Wealth UI** | | ⬜ | 14h | |
| └ AOS-820 | Wealth overview: net worth trend, allocation vs target, holdings with gains | Claude | ⬜ | 8h |  |
| └ AOS-821 | Guidance cards with "How we got this", policy editor, imports and tax report screens | Claude | ⬜ | 6h |  |
