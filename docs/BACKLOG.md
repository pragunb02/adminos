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

## ⬜ [AOS-E2](tickets/AOS-E2.md) — Data in (no typing) · P0 · ETA 31h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S2.1](tickets/AOS-S2.1.md)** | **Reliable ingestion pipeline** | | ⬜ | 3h | |
| └ AOS-201 | Decide the ingest_events processor (Kotlin vs Go); /ingest/sms → adapter; fix the API → worker payload contract and add contract tests | Claude | ⬜ | 3h |  |
| **[AOS-S2.2](tickets/AOS-S2.2.md)** | **Import bank statements** | | ⬜ | 6h | |
| └ AOS-202 | Statement upload end to end (pdf-parser, CSV/Excel, dates, debit/credit) | Claude | ⬜ | 6h |  |
| **[AOS-S2.3](tickets/AOS-S2.3.md)** | **iPhone SMS auto-capture** | | ⬜ | 5h | |
| └ AOS-203 | iPhone Shortcuts SMS adapter (raw text parsed in memory, never stored, D-33) + SMS parser + setup guide | Claude | ⬜ | 5h |  |
| **[AOS-S2.4](tickets/AOS-S2.4.md)** | **Cash quick-add** | | ⬜ | 2h | |
| └ AOS-204 | Cash quick-add (API + two-tap UI) | Claude | ⬜ | 2h |  |
| **[AOS-S2.5](tickets/AOS-S2.5.md)** | **Numbers I can trust** | | ⬜ | 15h | |
| └ AOS-205 | Correct debit/credit everywhere | Claude | ⬜ | 1h |  |
| └ AOS-206 | Deterministic merchant normalization (VPA → brand) | Claude | ⬜ | 2h |  |
| └ AOS-207 | Dedup across sources | Claude | ⬜ | 3h |  |
| └ AOS-208 | Self-transfer detection | Claude | ⬜ | 3h |  |
| └ AOS-209 | Refund/reversal linking | Claude | ⬜ | 2h |  |
| └ AOS-210 | Single categorizer with remembered corrections | Claude | ⬜ | 4h |  |

## ⬜ [AOS-E3](tickets/AOS-E3.md) — Console (core value) · P0 · ETA 22h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S3.1](tickets/AOS-S3.1.md)** | **Analytics API & design** | | ⬜ | 8h | |
| └ AOS-301 | Analytics API (series, breakdowns, comparisons) | Claude | ⬜ | 4h |  |
| └ AOS-302 | Choose chart library + design system | Claude + Pragun | ⬜ | 1h |  |
| └ [AOS-308](tickets/AOS-308.md) | Web: shared data-fetching layer (replace setState-in-effect) | Claude | ⬜ | 3h |  |
| └ [AOS-120](tickets/AOS-120.md) | Per-user timezone for date-range params | Claude | ⬜ | 1h |  |
| **[AOS-S3.2](tickets/AOS-S3.2.md)** | **Daily & monthly views** | | ⬜ | 6h | |
| └ AOS-303 | Today / this week view | Claude | ⬜ | 3h |  |
| └ AOS-304 | Monthly view | Claude | ⬜ | 3h |  |
| **[AOS-S3.3](tickets/AOS-S3.3.md)** | **Where my money goes** | | ⬜ | 5h | |
| └ AOS-305 | Categories and top merchants views | Claude | ⬜ | 3h |  |
| └ AOS-306 | Trend charts | Claude | ⬜ | 2h |  |
| **[AOS-S3.4](tickets/AOS-S3.4.md)** | **Transaction feed** | | ⬜ | 3h | |
| └ AOS-307 | Transaction feed: search, filter, edit, re-categorize | Claude | ⬜ | 3h |  |

## ⬜ [AOS-E4](tickets/AOS-E4.md) — More sources · P1 · ETA 18h

| ID | Title | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| **[AOS-S4.1](tickets/AOS-S4.1.md)** | **Gmail import** | | ⬜ | 6h | |
| └ AOS-401 | Gmail incremental sync, full body, sender filters, encrypted tokens | Claude | ⬜ | 6h |  |
| **[AOS-S4.2](tickets/AOS-S4.2.md)** | **WhatsApp capture** | | ⬜ | 9h | |
| └ AOS-402 | WhatsApp forward-to-bot (Cloud API) | Claude + Pragun | ⬜ | 6h |  |
| └ AOS-403 | WhatsApp export `.txt` import | Claude | ⬜ | 3h |  |
| **[AOS-S4.3](tickets/AOS-S4.3.md)** | **SMS history backfill** | | ⬜ | 3h | |
| └ AOS-404 | Mac chat.db SMS backfill | Claude | ⬜ | 3h |  |

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
