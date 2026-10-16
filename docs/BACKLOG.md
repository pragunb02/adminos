# Backlog board

Hierarchy: **Epic** (milestone) → **Story** (user-visible outcome) → **Task** (unit of work). Tickets live in `docs/tickets/`; templates are in `docs/tickets/_templates/`.

**IDs:** Epic `AOS-E<n>` · Story `AOS-S<n>.<m>` · Task `AOS-<n><nn>` (the hundreds digit is the milestone). IDs are permanent; never reuse them.

**Status:** ⬜ Todo · 🟦 In progress · 🟨 Done, unverified · ✅ Done · ⛔ Blocked · ❌ Dropped. **ETA** = focused hours (D-23).

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

## 🟦 [AOS-E3](tickets/AOS-E3.md) — Console (core value) · P0 · ETA 24h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S3.1](tickets/AOS-S3.1.md)** | **Analytics API & design** | | 🟦 | 8h | |
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
| **[AOS-S3.4](tickets/AOS-S3.4.md)** | **Transaction feed** | | ⬜ | 5h | |
| └ [AOS-611](tickets/AOS-611.md) | Exact money everywhere (D-30): every API money field is a string; web `lib/money.ts` | Claude | ✅ | 1.5h | 1.5h |
| └ [AOS-307](tickets/AOS-307.md) | Activity: search, filters in URL, edit sheet, re-categorize + undo; feed paging fix | Claude | ✅ | 3h | 2h |
| └ AOS-309 | Review suggested duplicates / transfers / refunds (D-45) | Claude | ⬜ | 2h |  |

## ⬜ [AOS-E4](tickets/AOS-E4.md) — More sources · P1 · ETA 18h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S4.1](tickets/AOS-S4.1.md)** | **Gmail import** | | ⬜ | 6h | |
| └ AOS-401 | Gmail incremental sync (real history ID), full body, sender filters, encrypted tokens, OAuth connect flow, cron skip rule (pipeline hand-off ✅ AOS-205) | Claude | ⬜ | 5h |  |
| **[AOS-S4.2](tickets/AOS-S4.2.md)** | **WhatsApp capture** | | ⬜ | 9h | |
| └ AOS-402 | WhatsApp forward-to-bot (Cloud API) | Claude + Pragun | ⬜ | 6h |  |
| └ AOS-403 | WhatsApp export `.txt` import | Claude | ⬜ | 3h |  |
| **[AOS-S4.3](tickets/AOS-S4.3.md)** | **SMS history backfill** | | ⬜ | 3h | |
| └ AOS-404 | Mac chat.db SMS backfill (delivered in AOS-203: `--backfill-days`) | Claude | ✅ | 3h | 0h |

## ⬜ [AOS-E5](tickets/AOS-E5.md) — Money admin & smart extras · P1 · ETA 26h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S5.1](tickets/AOS-S5.1.md)** | **Scheduler & notifications** | | ⬜ | 7h | |
| └ AOS-501 | Scheduler (detectors, reminders, briefing) | Claude | ⬜ | 3h |  |
| └ AOS-504 | Real notifications (push/email) | Claude | ⬜ | 4h |  |
| **[AOS-S5.2](tickets/AOS-S5.2.md)** | **Recurring payments & dues** | | ⬜ | 8h | |
| └ AOS-502 | Recurring / UPI AutoPay mandate tracker | Claude | ⬜ | 4h |  |
| └ AOS-503 | Dues & renewals calendar + reminders | Claude | ⬜ | 4h |  |
| **[AOS-S5.3](tickets/AOS-S5.3.md)** | **Alerts & weekly briefing** | | ⬜ | 5h | |
| └ AOS-505 | Unusual-charge alerts (wire existing rules) | Claude | ⬜ | 2h |  |
| └ AOS-506 | Weekly briefing with real data | Claude | ⬜ | 3h |  |
| **[AOS-S5.4](tickets/AOS-S5.4.md)** | **Documents vault** | | ⬜ | 6h | |
| └ AOS-507 | Documents vault | Claude | ⬜ | 6h |  |

## ⬜ [AOS-E6](tickets/AOS-E6.md) — Public-ready · P2 · ETA 45h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S6.1](tickets/AOS-S6.1.md)** | **Security & DPDP compliance** | | ⬜ | 11h | |
| └ [AOS-121](tickets/AOS-121.md) | Refresh-token rotation with reuse detection | Claude | ⬜ | 2h |  |
| └ AOS-601 | Encrypt tokens at rest; refuse default secrets; require Pub/Sub audience | Claude | ⬜ | 3h |  |
| └ AOS-602 | DPDP: consent records, export/delete, access logs | Claude | ⬜ | 8h |  |
| **[AOS-S6.2](tickets/AOS-S6.2.md)** | **Public ingestion paths** | | ⬜ | 26h | |
| └ AOS-603 | Gmail forwarding-address inbound | Claude | ⬜ | 6h |  |
| └ AOS-604 | Android app (SMS + notification listener) | Claude | ⬜ | 20h |  |
| **[AOS-S6.3](tickets/AOS-S6.3.md)** | **Launch** | | ⬜ | 8h | |
| └ [AOS-605](tickets/AOS-605.md) | Onboarding + hosting (edge proxy owns XFF, D-32) | Claude + Pragun | ⬜ | 8h |  |
| └ [AOS-607](tickets/AOS-607.md) | JWT key now uses the whole `JWT_SECRET` (found by AOS-311) | Claude | ✅ | 0.5h | 0.5h |
| └ [AOS-608](tickets/AOS-608.md) | Token encryption: whole 256-bit key, row-bound ciphertext; no public default secrets | Claude | ✅ | 1.5h | 1h |
| └ [AOS-609](tickets/AOS-609.md) | Gmail Pub/Sub webhook authentication (issuer, service account, fail closed) | Claude | ✅ | 1h | 0.75h |
| └ [AOS-610](tickets/AOS-610.md) | Login hardening: inactive users, OAuth form encoding, Secure cookies, session revoke | Claude | ✅ | 1.5h | 1h |
| └ AOS-606 | Dev-dependency advisories: ESLint chain (`eslint-config-next` → `braces`, high, dev-only); upgrade when a non-breaking fix exists | Claude | ⬜ | 0.5h |  |
