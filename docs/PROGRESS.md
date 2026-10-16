# Progress

_Last updated: 2026-10-16 by a Claude session. Update this file at the end of every session._

## Where we are

| Milestone (see BRD §6) | Status |
|---|---|
| 1. Foundation runs | 🟦 All code done. Left: Pragun's OAuth client (AOS-111) → real login test (AOS-112) |
| 2. Data in | 🟦 Started. Intake → Kotlin pipeline → transactions works end to end (AOS-201); sources next |
| 3. Dashboards | 🟠 Pages exist; no daily/monthly series or charts yet |
| 4. Automatic sources (SMS app, WhatsApp) | ⚪ Not started |
| 5. Smart extras | 🟠 Rule code exists but nothing ever calls it |
| 6. Public-ready | ⚪ Not started |

**Active branch:** `feat/personal-console`, not committed. The changes on it are in
"Uncommitted changes" below.

**Next step:** story S1.2, Google login (~~AOS-106~~, ~~AOS-107~~, ~~AOS-108~~ done; ~~AOS-109~~, ~~AOS-110~~ done; waiting on AOS-111 (Pragun: OAuth client) for AOS-112; ~~AOS-113~~, ~~AOS-201~~ done. ~~AOS-203~~ iPhone SMS done (Pragun: AOS-211 setup once logged in). ~~AOS-202~~ statement upload done (Pragun: AOS-212 with real statements). ~~AOS-207~~ cross-source dedup done. ~~AOS-208~~, ~~AOS-209~~, ~~AOS-210~~, ~~AOS-204~~, ~~AOS-205~~, ~~AOS-206~~ done (accuracy engine code complete; needs real-data check AOS-211/212). Milestone 3 console started (D-45): ~~AOS-120~~, ~~AOS-301~~, ~~AOS-308~~, ~~AOS-310~~, ~~AOS-311~~ done; ~~AOS-607~~, ~~AOS-312~~ done; Plan 2: ~~AOS-608~~, ~~AOS-609~~, ~~AOS-610~~ done (all 7 security bugs fixed); next AOS-611 money, AOS-612 bills, AOS-613 notifications, then AOS-302 spec PR, AOS-302 design spec, then AOS-303/304, 303/304, 305/306, 307, 309; AOS-107 JSON contract, AOS-108 web config, AOS-109 refresh/logout, AOS-111 Pragun creates the OAuth client, AOS-112 end-to-end).
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
| DB schema (Flyway `db/migration`, 20 tables) | ✅ | ✅ | Migrates on startup (verified 2026-10-16); enum/JSONB via stringtype |
| API routes (~35) | ✅ | ❌ | Auth reads only the Bearer header, but the web sends a cookie; JSON snake_case vs camelCase |
| Google login | ✅ | 🟡 | Web↔API path verified live; needs Pragun's OAuth client (AOS-111) for the real check (AOS-112) |
| API → worker queue | ✅ | ❌ | `AsynqPublisher` stringifies payload values; split workers drop task types; manual sync never enqueues |
| SMS ingest (API + Go) | ✅ | ❌ | Payload bug; every transaction saved as `debit`; no phone app |
| Gmail ingest (Go → pipeline) | 🟡 | ❌ | Writes `ingest_events` (AOS-205), so dedup/transfers/refunds/categories apply. Still: snippet only, fake history ID, no Pub/Sub watch (AOS-401). Needs login + OAuth (AOS-111) |
| Statement import (API → parser) | ✅ | ✅ | PDF/CSV/Excel, passwords; files never stored (D-37) |
| Statement parser (Python) | ✅ | ✅ | Called by the API; 31 tests |
| Cash quick-add (AOS-204) | ✅ | 🟡 | `POST /transactions/cash` + "+ Cash" button; through the pipeline; tested (unit, route, Postgres). Not yet used live: needs login (AOS-112) |
| Web pages (8) | ✅ | ❌ | Call the real API; blocked by auth |
| Notifications | 🟡 | ❌ | FCM stubbed; email marked sent without sending |
| Scheduler | ❌ | ❌ | Nothing runs briefings, reminders or detectors |
| `services/api/Dockerfile` | ✅ | ✅ | JDK 25 JRE, non-root, healthcheck (AOS-110) |
| Android SMS app | ❌ | ❌ | Not in repo |

## Business logic progress

**Live** means it runs in a real flow. **Dead** means the code exists but nothing calls it.

| Feature | Rule (as implemented) | State |
|---|---|---|
| Merchant normalization (Kotlin `MerchantNormalizer`, AOS-201/206, D-44) | Brand from the catalogue (104 brands, incl. legal names; whole words, longest wins), else the payee name cleaned from the narration (UPI/POS/gateway structure removed), else the UPI handle | Live |
| Categorization, Go | Removed in AOS-205: Gmail goes through the Kotlin pipeline | Removed |
| Categorization, Kotlin (`CategorizationService.kt`, AOS-210, D-41) | The only categorizer for pipeline sources. Order: your remembered rule (UPI ID, else merchant), then 22 known merchants, then 7 whole-word patterns, else Other. It never guesses Transfer (TransferStep does). Corrections re-label past spend/refunds except hand-set ones | Live |
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
1. ~~AOS-102 Flyway~~ and ~~AOS-103 stringtype~~ fixed 2026-10-16.
2. ~~AOS-106 cookie auth~~ and ~~AOS-107 contract~~ fixed.
3. ~~AOS-201~~ SMS no longer crosses Kotlin→Go (D-34). Gmail/PDF Go paths → AOS-401/202.
4. ~~AOS-110 missing API Dockerfile~~ fixed; the whole stack runs.
5. ~~AOS-205~~ fixed: Gmail now writes ingest events with a detected direction (D-43); no Go path writes `transactions` any more.
7. AOS-401 — the Gmail worker stores a made-up `history_id` (`history_<unix time>`) after each sync, so incremental sync can't work yet.
6. AOS-601 — Gmail tokens stored in plain text (`gmail_handler.go:185`).
8. AOS-307 — `GET /transactions` sends `amount` as a float and has no `nature` field (breaks D-30; the feed can't tell refunds/transfers apart).
9. Onboarding copy still describes an Android SMS app (D-19: iPhone); the subscriptions "cancellation draft" modal can never open (dead code). Fold into the console redesign (AOS-302…).
11. ~~AOS-607~~ fixed: the whole `JWT_SECRET` is the HMAC key. Existing sessions and device tokens must be re-issued once (sign in again; new Mac SMS reader token).
10. AOS-606 — `npm audit`: 5 high, dev-only (ESLint chain → `braces`); the only fix npm offers is a breaking downgrade.

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
  - **Process incident (PR #43):** GitGuardian reported 4 "Generic Password" findings and the PR was merged anyway. My wait loop merged after a timeout instead of stopping. The findings were false positives: the fail-fast message text inside `${DB_PASSWORD:?…}` in `docker-compose.yml`, and no secret was committed. Hotfix PR uses a bare `${DB_PASSWORD:?}`. `scripts/merge-pr.sh` now merges only when every check is `success` (CLAUDE.md updated). **Pragun: mark GitGuardian incident 38276594 as a false positive in the dashboard.**
  - AOS-610: deactivated/deleted users can't log in or refresh (403, session revoked); OAuth token form URL-encoded; `Secure` cookies unless the host is exactly loopback; revoking another user's session → 404, not audited.
  - AOS-609: Gmail push webhook fails closed (503 until `GOOGLE_PUBSUB_AUDIENCE` + `GOOGLE_PUBSUB_SERVICE_ACCOUNT` are set); tokens must be from Google issuers and our push service account with a verified email. Closes the AOS-601 webhook hole.
  - AOS-608: token encryption uses the whole 256-bit key with row-bound AAD (`v2:`); no public default secrets: DB mode requires real `JWT_SECRET`/`TOKEN_ENCRYPTION_KEY`/`DB_PASSWORD`, compose fails fast without `.env`. **`docker compose up` now needs a `.env`** (see `.env.example`).
  - AOS-312: API coverage via Kover: 70.7%→**99.63% lines**, 47.1%→**95.41% branches**; 1,137 tests; gate `koverVerify` 90/85; 5 parallel agents. Found 18 real bugs (7 security) → AOS-608…613; 19 `@Disabled` regression tests wait for the fixes.
  - AOS-607: JWT HMAC key = the whole secret (was its first 32 bytes); regression test fails on the old code; end-to-end forged-token check. API unit 320, integration 51.
  - AOS-311: Playwright end-to-end: 131 tests (129 pass, 2 skipped by design) on desktop Chrome, iPhone Safari, Android Chrome + full stack (real API, signed test session). Fixed WCAG contrast everywhere and an unreachable Log out on phones. Found AOS-607.
  - AOS-310: web unit tests: Vitest + Testing Library + MSW; 156 tests, 99.1% lines / 97.3% branches; gate 90/85 (D-47). Found and fixed 4 UI bugs (cash Escape, "↓changed", 2 onboarding dead ends) and unlabeled inputs.
  - AOS-308: web data layer = TanStack Query (`lib/query.ts`, `Providers`); all pages migrated; action errors no longer swallowed; lint `set-state-in-effect` back to error, 0 warnings; npm audit 0. Not browser-tested (needs login).
  - AOS-301: analytics API `/api/v1/analytics/{summary,series,categories,merchants}`: one SQL fact query (local day × nature × category × merchant) → `AnalyticsService`; spent = spend − refunds, received = income, total = Σ categories = Σ buckets (D-46). Unit 318, integration 51 (Postgres = in-memory in two zones).
  - AOS-120: per-user timezone: `tz` claim in the access token → `call.userZone` → date-only params are whole days in the user's zone. Unit 304, integration 50.
  - AOS-206: one merchant catalogue (`resources/merchants/catalogue.json`, 104 brands, validated on load) shared by normalizer + categorizer; `NarrationCleaner` pulls payee names out of UPI/POS/gateway narrations. Unit 302, integration 50.
  - AOS-205: Go Gmail worker writes `ingest_events` (message ID ref, direction by D-43, unclear → needs-review, NULL payment method) instead of `transactions`; no silent "success" when Gmail is unreachable; Go categorizer removed. Go unit + integration (real Postgres, migrated to V16) green; API unit 294, integration 50.
  - AOS-204: cash quick-add (D-42): API through the common intake (V16 `ingest_events.category`, chosen category kept, idempotent clientRef, cash never deduped), ATM/cash withdrawals → transfer to cash, web "+ Cash" (amount → tap category). Unit 293, integration 50; web typecheck/lint/build green.
  - AOS-210: single categorizer with remembered corrections (D-41, V15 `category_rules`): one rule per payee, future and past re-labelled (not hand-set), `remember:false` opt-out. Removed wrong guesses (unknown UPI/NEFT/IMPS/Paytm/PhonePe/GPay → Transfer), substring false matches (Motorola → Ola), dead strategy and AI-queue code. Unit 283, integration 49.
  - AOS-209: refunds/reversals (D-40): reversals net to zero; refunds capped at the original, same merchant, ≤90 days, net their category's spend; works in either order. Unit 275, integration 47.
  - AOS-208: transaction nature (D-39) drives totals; own-account transfer pairs + card bill payments; generic relation side effects; suggestions API generalised. Unit 262, integration 45.
  - AOS-207: cross-source dedup (D-38, research-backed): RRN first, score bands, 1:1, payment-type windows, links table (V13), enrich blanks only, review API. Fixed: transaction update silently dropped account/VPA/method. Unit 251, integration 44.
  - AOS-202: statement import end to end (Python parser: CSV/XLSX/XLS/PDF+password, column roles, reconciliation; Kotlin import with occurrence-aware fingerprints; Import page). Retired R2/upload/Go PDF. Fixed: VPA regex, API Docker build on this network (official Gradle image). Unit 228, integration 42 (incl. real Python image via Testcontainers).
  - AOS-203: iPhone SMS end to end. Parsers (5 banks + generic, drop rules); device tokens (D-36, fixed latent token-type hole); POST /ingest/sms/raw; Mac Messages reader (Go, read-only chat.db, business senders only, localhost only, smoke-tested on this Mac); Settings → Connected devices; setup guide. D-35 transport. Unit 218, integration 41, reader 10.
  - AOS-201: Kotlin ingest pipeline (D-34): claim (SKIP LOCKED) → ToTransaction → NormalizeMerchant (deterministic) → Categorize → atomic exactly-once write. /ingest/sms → adapter; Go SMS worker retired. Integration tests caught a V10 enum migration that would have stopped startup (split V10/V11). Unit 192, integration 40.
  - AOS-113: common intake POST /ingest/events (V9 ingest_events outbox, idempotent, decimal-string money). D-33 raw SMS privacy. Fixed: malformed bodies were 500 → 400. Unit 179, integration 36. Story S1.4 done.
  - AOS-110: API image (JDK 25, non-root, healthcheck); compose waits for a healthy API; fixed worker commands; **full stack runs**. Story S1.3 done.
  - AOS-109: refresh via HttpOnly cookie, tokens out of response bodies, logout = this device only and works after JWT expiry, web refresh-once retry + Log out button. Unit 165. Rotation → AOS-121.
  - AOS-108: same-origin /api proxy (D-32). Found live: the web guard blocked the login API call (login could never have worked); empty-body 401s crashed the web client; client IP behind proxy (XForwardedHeaders + clientIp; edge proxy required for public, AOS-605). Unit 158.
  - AOS-107: contract audit. **camelCase** (D-31, switched from snake_case after the audit). Fixed: transaction filters, the Flagged tab, **pagination broken against the DB** (cursor format), silent/opaque param errors. QueryParams helpers + route contract tests. Unit 154, integration 32.
  - AOS-106: API accepts the access_token cookie (header first); 6 auth tests; route test uses production auth. Unit 148/148.
  - AOS-105: integration tests for all 15 repositories (31/31). Found and fixed: metadata JSON crashed on save in 6 repos (`JsonColumns`, exact decimals); 9 tests silently skipped by JUnit (now run + guard). **Story S1.1 done.** Unit 142/142.
  - AOS-104: closed (integration 9/9 met by AOS-115/103/102). Merge rule: wait for the security check (CLAUDE.md).
  - AOS-102: Flyway owns migrations (D-12); startup fails fast; real `./gradlew run` verified (8 applied, restart 0).
  - AOS-103: JDBC stringtype=unspecified; integration tests 8/8 for the first time.
  - AOS-119: Postgres 18.6 / Redis 8.10 (compose + tests), ports bound to localhost. **Story S1.0 done** (4.5h vs 11h ETA).
  - AOS-118: workers on Go 1.27 with latest modules; first verified build/test; Dockerfile fixed (non-root). Go runs via Docker (proxy.golang.org blocked).
  - AOS-117: PDF parser on Python 3.14 with latest libraries; Docker smoke test passed.
  - AOS-116: web on Next 16.4, React 19.3, Tailwind 4.3, ESLint 10, TS 6.0.3 (D-29), Node 24. Lint runs for the first time; set-state-in-effect → AOS-308.
  - AOS-115: API on latest stable (Gradle 9.8, Kotlin 2.4, Ktor 3.6, Exposed 1.5, Flyway 13, Koin 4.2, Testcontainers 2, JDK 25). Integration tests now run (they never did); all migrations apply on real Postgres; 4 enum failures → AOS-103.
  - AOS-101: API compiles again; 3 regression tests; 130/130 green.
  - AOS-008: design principles, patterns, design-before-code, dependency policy (D-27, D-28).
  - AOS-009: dependency audit; most libraries 1+ majors behind; worker Dockerfile Go version broken → story S1.0.
  - AOS-006: engineering standards (`docs/ENGINEERING.md`, D-26); AOS-007 audit filed.
  - AOS-004: Amazon-style tickets (7 epics, 23 stories, 20 task tickets), templates, board, decision index (D-23…D-25).
  - BRD v2 from research (problems P1–P8; iPhone Shortcuts, WhatsApp forward-to-bot, Gmail
    forwarding for public, accuracy engine, DPDP gate).
