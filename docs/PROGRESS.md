# Progress

_Last updated: 2026-10-16 by a Claude session. Update this file at the end of every session._

## Where we are

| Milestone (see BRD §6) | Status |
|---|---|
| 1. Foundation runs | 🔴 Broken. DB setup fails, login broken, `docker compose up` fails |
| 2. Data in | 🟠 Code exists, but the flow from API to workers is broken |
| 3. Dashboards | 🟠 Pages exist; no daily/monthly series or charts yet |
| 4. Automatic sources (SMS app, WhatsApp) | ⚪ Not started |
| 5. Smart extras | 🟠 Rule code exists but nothing ever calls it |
| 6. Public-ready | ⚪ Not started |

**Active branch:** `feat/personal-console`, not committed. The changes on it are in
"Uncommitted changes" below.

**Next step:** story S1.0 dependency upgrades (pending Pragun's OK on order), then AOS-102…104.
Board: `docs/BACKLOG.md`. Tickets: `docs/tickets/` (Epic → Story → Task, D-25).

**BRD status:** v2 approved 2026-10-16. Revised with research; decisions D-15 to D-22.
New Milestone 1 scope adds the common intake endpoint; Milestone 2 adds iPhone Shortcuts SMS,
cash quick-add and the accuracy engine.

---

## Code progress by component

Verified on 2026-10-16 on `main` (before branch changes):
- API: `./gradlew test` passes.
- Web: `next build` passes.
- Go workers: not run (Go not installed).

| Component | Built | Works end to end | Notes |
|---|---|---|---|
| DB schema (`infra/migrations`, 20 tables) | ✅ | ❌ | Flyway ignores `001_*` names; enum/JSONB columns mapped as varchar in Exposed |
| API routes (~35) | ✅ | ❌ | Auth reads only the Bearer header, but the web sends a cookie; JSON snake_case vs camelCase |
| Google login | ✅ | ❌ | See above; `NEXT_PUBLIC_GOOGLE_CLIENT_ID` not configured |
| API → worker queue | ✅ | ❌ | `AsynqPublisher` stringifies payload values; split workers drop task types; manual sync never enqueues |
| SMS ingest (API + Go) | ✅ | ❌ | Payload bug; every transaction saved as `debit`; no phone app |
| Gmail ingest (Go) | 🟡 | ❌ | Parses snippet only; no incremental sync; no Pub/Sub watch |
| PDF upload (API → R2) | ✅ | 🟡 | Works if R2 is configured; otherwise the file is discarded |
| PDF parser (Python) | ✅ | ❌ | Standalone HTTP; nothing calls it; dates unparsed; debit/credit dropped |
| Manual add | ❌ | ❌ | Not built |
| Web pages (8) | ✅ | ❌ | Call the real API; blocked by auth |
| Notifications | 🟡 | ❌ | FCM stubbed; email marked sent without sending |
| Scheduler | ❌ | ❌ | Nothing runs briefings, reminders or detectors |
| `services/api/Dockerfile` | ❌ | ❌ | Missing; compose references it |
| Android SMS app | ❌ | ❌ | Not in repo |

## Business logic progress

**Live** means it runs in a real flow. **Dead** means the code exists but nothing calls it.

| Feature | Rule (as implemented) | State |
|---|---|---|
| Merchant normalization (Go `normalizer.go:60`) | Uppercase, then first of 13 aliases contained (GPay, Swiggy, Uber…), else title-case. Map order is random, so overlapping names can misfire (`UBER EATS` → Uber) | Live, buggy |
| Categorization, Go (`categorizer.go:23`) | 10 ordered substring rules; no match gives `other` (0.3) | Live, buggy (`jio cinema` → utilities) |
| Categorization, Kotlin (`CategorizationService.kt`) | 25 known merchants, then 7 regex rules, then UPI → transfer, else other. 11 categories | Dead |
| Dedup (`normalizer.go:47`) | SHA256(user, amount, merchant, date-to-hour, last4) in `ingestion_fingerprints` | Live |
| Subscription detection (`SubscriptionDetectorService.kt`) | 90 days of debits per merchant, ≥2 charges. Day-gap mean/sd: weekly 6–8, monthly 25–35, quarterly 85–95, yearly 355–375. Price change >5% | Dead |
| Waste score (Go) | +0.4 no logins & no usage emails; +0.3 paid 3+ months with no logins; +0.1 if 6+; +0.2 no usage emails. >0.7 = unused | Never triggered |
| Anomalies (`AnomalyRules.kt`) | >3× 30-day category average; non-INR; 01:00–05:00 IST; ₹1–2 (card testing); same merchant+amount within 24h | Dead |
| Bill detection, Gmail | 12 keywords in the email, saved as upcoming bill | Live (due_date bug) |
| Bill payment matching | Name similarity >0.8 (Jaro-Winkler) and amount within 5% | Dead |
| Reminders | Bills due within 7 days; remind on user days (default 3,1); past-due → overdue | Dead |
| Onboarding nudges | Gmail days 1/3/7, SMS 3/5/7, max 3 | Dead |
| Quiet hours | 22:00–08:00 default; only anomalies bypass | Dead |
| Dashboard (`DashboardRoutes.kt`) | Last 7 days of debit spend; counts of subscriptions, bills (30d) and anomalies; action items | Live |
| Spend summary (`/transactions/summary`) | Debit totals by category, default 30 days | Live |
| Daily/monthly series, trends, top merchants | — | Not built (core of Milestone 3) |
| Weekly briefing (Go + Claude) | Prompt: summary, attention, subscription review, one tip. Data query returns zeros | Never triggered, data stubbed |

## Known issues (priority order)
0. ~~AOS-101 compile break~~ fixed 2026-10-16 (130/130 tests green).
1. AOS-102, AOS-103 — DB: Flyway naming (D-12) and JDBC `stringtype` (D-13).
2. AOS-106, AOS-107 — Auth: cookie vs Bearer header; JSON field naming.
3. AOS-201 — queue payload contract between Kotlin and Go.
4. AOS-110 — missing API Dockerfile.
5. AOS-205 — every transaction stored as `debit`; empty `payment_method` violates the enum.
6. AOS-601 — Gmail tokens stored in plain text (`gmail_handler.go:185`).

## Uncommitted changes on `feat/personal-console` (AOS-002…004 docs, AOS-100 schema)
- **New docs:** `CLAUDE.md`, `docs/BRD.md`, `docs/DECISIONS.md`, `docs/PROGRESS.md`.
- **`001_create_enums.sql`:** each enum now holds the union of its Kotlin and SQL values; added `anomaly_explain` to `job_type` (D-9).
- **`agent/Models.kt`, `notifications/Models.kt`:** added the SQL-only values to the Kotlin enums (D-9).
- **`004`:** removed `create_hypertable` (D-8).
- **`003`, `004`, `006`:** array columns changed to TEXT (D-10).
- None of the above is tested yet.

## Session log
- **2026-04-03 → 04-05:** original MVP built across PRs #1–#6 (ingestion, financial intelligence, agent layer, notifications, web app, real infra wiring).
- **2026-10-16:**
  - Full audit of the codebase.
  - Re-scoped to a personal spending console (D-4 … D-7).
  - Started the DB fixes (D-8 … D-10).
  - Wrote these docs.
  - AOS-101: API compiles again; 3 regression tests; 130/130 green.
  - AOS-008: design principles, patterns, design-before-code, dependency policy (D-27, D-28).
  - AOS-009: dependency audit; most libraries 1+ majors behind; worker Dockerfile Go version broken → story S1.0.
  - AOS-006: engineering standards (`docs/ENGINEERING.md`, D-26); AOS-007 audit filed.
  - AOS-004: Amazon-style tickets (7 epics, 23 stories, 20 task tickets), templates, board, decision index (D-23…D-25).
  - BRD v2 from research (problems P1–P8; iPhone Shortcuts, WhatsApp forward-to-bot, Gmail
    forwarding for public, accuracy engine, DPDP gate).
