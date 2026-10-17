# Progress

_Last updated: 2026-10-17 by a Claude session. Update this file at the end of every session._

## Where we are

| Milestone (see BRD §6) | Status |
|---|---|
| 1. Foundation runs | 🟦 All code done. Left: Pragun's OAuth client (AOS-111) → real login test (AOS-112) |
| 2. Data in | 🟦 Started. Intake → Kotlin pipeline → transactions works end to end (AOS-201); sources next |
| 3. Dashboards | 🟦 Code complete (AOS-301…313, Epic E3 stories all ✅): Home, Month, Spending, Trends, Activity, Review. Left: check on real data (needs AOS-111, AOS-211/212) |
| 4. Automatic sources (SMS app, WhatsApp) | ⚪ Not started |
| 5. Smart extras | 🟠 Rule code exists but nothing ever calls it |
| 6. Hardening and security (P0, D-91) | 🟦 AOS-614 done; S6.4 in progress |
| 7. Smart features (E7) | 🟦 Wave 1 in progress |
| 8. Wealth (E8) | 🟦 Research (AOS-801) |
| 9. Spending coach (E9) | 🟦 Started (before E8 build) |

**Active branch:** `feat/personal-console`, not committed. The changes on it are in
"Uncommitted changes" below.

**Next step:** AOS-403 WhatsApp export merged; AOS-402 (bot) needs Pragun's WhatsApp Cloud API setup. Integrate the agents' PRs (AOS-511/512, AOS-405/406, AOS-701 research + E7). Earlier: Epic E5 code complete (S5.1–S5.4 ✅; AOS-509 merged). ~~AOS-401~~ Gmail sync merged (🟨 until a live Google check). Then: proposed (awaiting Pragun) AOS-511 categorization eval harness, AOS-512 AI fallback categorizer for "Other"; follow-ups from the agents (Gmail `users.watch`, a web "Connect Gmail" screen, subscription-detector fixes). Pragun: Cloudflare R2 bucket + token (`DOCS_S3_*`), VAPID keys and SMTP settings in `.env`; back up `TOKEN_ENCRYPTION_KEY`; iPhone → Add to Home Screen → Settings → Turn on notifications; AOS-111 OAuth client; AOS-211/212 real SMS and statements; GitGuardian incidents 38276594, 38288540, 38289280 → false positive.
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
| Gmail ingest (Go → pipeline) | ✅ | 🟡 | Incremental sync from real history IDs, financial senders only, encrypted tokens, daily fallback (AOS-401, D-59). Still: no `users.watch` (push), needs login + OAuth (AOS-111) |
| Statement import (API → parser) | ✅ | ✅ | PDF/CSV/Excel, passwords; files never stored (D-37) |
| Statement parser (Python) | ✅ | ✅ | Called by the API; 31 tests |
| Cash quick-add (AOS-204) | ✅ | 🟡 | `POST /transactions/cash` + "+ Cash" button; through the pipeline; tested (unit, route, Postgres). Not yet used live: needs login (AOS-112) |
| Web pages (8) | ✅ | ❌ | Call the real API; blocked by auth |
| Notifications | ✅ | 🟡 | In-app, SMTP email (AOS-504) and Web Push incl. iPhone Home Screen app (AOS-510); quiet-hours hold, retries (D-51, D-52). Needs SMTP/VAPID settings in `.env` |
| Scheduler | ✅ | 🟡 | Runs bill/renewal reminders, nudges, subscription detection, pending notifications, weekly briefings, once per slot (D-50) |
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
| Subscription detection (`SubscriptionDetectorService.kt`) | 400 days of spends per merchant (user dates), ≥2 charges. Day-gap mean/sd: weekly 6–8, monthly 25–35, quarterly 85–95, yearly 355–375. Price change >5%; calendar next date; catalogue category; ends after two silent cycles (D-65) | Scheduled nightly 04:00 IST for active users (AOS-501) |
| Waste score | Kotlin `WasteScorer`: named factors (price rise, duplicate service, cost share, long-running, dormant AutoPay, trial ending, marked unused); ≥ 0.70 flags with reasons (D-58) | Live nightly 05:00 (AOS-509); Go handler superseded |
| Anomalies (`AnomalyRules.kt`) | >3× the 30 days before (≥3 spends, from ₹2,000); non-INR; 01:00–04:59 user time; ₹1–2 (card testing); same merchant+amount within 24h | Live on every new spend (AOS-505); alert if confidence ≥ 0.7 and recent (D-53) |
| Bill detection, Gmail | 12 keywords in the email; ISO due date (day-first), type, card last 4; no due date → skipped | Live (AOS-503) |
| Bill payment matching | Full (±5%) or minimum due (±1%), in the bill window, with evidence (card number / name / only card bill); ties match nothing (D-54) | Live on every new transaction + daily sweep (AOS-508) |
| Reminders | Bills and subscription renewals, on the user's days (default 3,1; up to 30), on the user's own date; days sent stored (V21); past-due → overdue | Daily 09:00 (AOS-501, AOS-503); delivered in-app, email, Web Push |
| Onboarding nudges | Gmail days 1/3/7, SMS 3/5/7, max 3 | Scheduled daily 11:00 IST (AOS-501) |
| Quiet hours | 22:00–08:00 default; only anomalies bypass | Dead |
| Dashboard (`DashboardRoutes.kt`) | Last 7 days of debit spend; counts of subscriptions, bills (30d) and anomalies; action items | Live |
| Spend summary (`/transactions/summary`) | Debit totals by category, default 30 days | Live |
| Daily/monthly series, trends, top merchants | — | Not built (core of Milestone 3) |
| Weekly briefing | From the console's analytics + dues, template-written (D-57); user's day/time/zone; once per week | Live (AOS-506); legacy Go + Claude handler unused |

## Known issues (priority order)
0. ~~AOS-101 compile break~~ fixed 2026-10-16 (130/130 tests green).
1. ~~AOS-102 Flyway~~ and ~~AOS-103 stringtype~~ fixed 2026-10-16.
2. ~~AOS-106 cookie auth~~ and ~~AOS-107 contract~~ fixed.
3. ~~AOS-201~~ SMS no longer crosses Kotlin→Go (D-34). Gmail/PDF Go paths → AOS-401/202.
4. ~~AOS-110 missing API Dockerfile~~ fixed; the whole stack runs.
5. ~~AOS-205~~ fixed: Gmail now writes ingest events with a detected direction (D-43); no Go path writes `transactions` any more.
7. ~~AOS-401 fake `history_id`~~ fixed: real history IDs, history.list, 30-day filtered fallback (D-59).
6. ~~AOS-601 Gmail tokens in plain text~~ fixed in AOS-401: the worker shares the API's TokenEncryptor v2 format.
8. ~~AOS-307 float amounts / missing nature~~ fixed in AOS-611.
9. Onboarding copy still describes an Android SMS app (D-19: iPhone); the subscriptions "cancellation draft" modal can never open (dead code). Fold into the console redesign (AOS-302…).
11. ~~AOS-607~~ fixed: the whole `JWT_SECRET` is the HMAC key. Existing sessions and device tokens must be re-issued once (sign in again; new Mac SMS reader token).
12. ~~AOS-503 reminder days not stored, server dates, Gmail bills without a due date~~ fixed (also: the look-ahead stopped at 7 days though preferences allow 30; subscription metadata dropped on update).
13. ~~AOS-505 anomaly windows used "now"~~ fixed. ~~AOS-508 card bills never matched~~ fixed (also: Postgres bill update dropped metadata, fixed). ~~AOS-506 `AsynqPublisher` stringified payloads~~ fixed. (was: turns every payload value into a string.
14. `GET /notifications` lists every channel's record, so one reminder appears up to 3 times; an inbox should list in-app only (with the inbox screen task).
15. ~~Subscription detector issues (found by AOS-509) and the fake cancel~~ fixed in AOS-513; also yearly plans were undetectable (90-day window) and next dates drifted (+30 days).
16. ~~Flaky Activity e2e~~ fixed in AOS-313 (waits for the feed request).
17. `ConnectionRepository.update` writes every column: a connect/disconnect racing a worker sync can rewind `history_id` (found in AOS-405; new watch code writes only its own columns). Fix: narrow updates for connect/disconnect.
18. Gmail push bursts aren't coalesced: the worker never marks a sync session `in_progress`, so "queued" may already be running. Fix: worker sets `in_progress`, then coalesce (found in AOS-405).
19. Connect Gmail takes `redirectUri` from the client; Google only accepts registered URIs, so it isn't exploitable, but checking it against the app's own URL would add depth. If the session expires during consent, login drops the code (start again; a return-to on login would be nicer). Both from AOS-406.
20. Non-JSON bodies to JSON routes return 500 instead of 415 (AOS-633, in progress).
21. Account balances are dropped at ingestion (SMS "Avl Bal", statement balances); net worth and "money left" need them (AOS-814 in E8).
22. Subscription detection is too broad: any regularly charged merchant (e.g. a weekly grocery shop) can be called a subscription; after a monthly → yearly switch it keeps the monthly cycle and marks the subscription ended.
23. `StatementImportService` and `RateLimiting.kt` use `!!` / `System.currentTimeMillis()` (ENGINEERING §2).
24. Web unit tests that wait 1–5 s (Documents, Briefings, CashQuickAdd, Import, Home) time out under heavy parallel load; they pass on a quiet run.
25. The Playwright session cookie is set without HttpOnly (test-only), so "no token in storage" specs can't check `document.cookie`.
26. `PATCH /transactions/{id}` calls the repository directly (layer rule).
27. AI follow-ups from the AOS-702/703 reviews: SDK retries can overshoot the cost cap by a bounded amount; IFSC and short account numbers not masked (partly done in AOS-705); no DB CHECK on `ai_calls.model`; sanitise model `reason` before display; warn at start-up when an eval record is synthetic; `evals.json` loads with AI off; a test for a trickling Ollama body + `@Volatile`; per-code-point digit mapping in `PiiMasker`; model weights pinned by digest in eval records; a YAML-aware compose exposure test.
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
- **2026-10-18:**
  - AOS-908 (integrated by the integration agent): savings rate (D-87) as the Home headline with its 12-month trend; last month's leaks as a SIP over 5/10 years with editable assumptions and the formula; too-little-data state; no schema change. Integration: Home e2e scopes its line-chart check to "Running total"; the savings 500 e2e allows for the app's automatic retries.
  - AOS-907 (integrated by the integration agent): goals (D-86): cut a lens by X% vs a frozen 3-month baseline or keep it under ₹Y, weekly progress and no-spend streaks on the leaks page and Home; migration V37 (was tentative V43); IdorTest row for `DELETE /coach/goals/{id}`.
  - AOS-906 (integrated by the integration agent): Sunday report card (D-85) as a section of the weekly briefing: week vs usual, best and worst lens, limit outcomes, "a suggested limit is ready", exactly one rule-based suggestion; `BriefingSection` port, briefing format `weekly-v2`; no schema change.
  - AOS-905 (integrated by the integration agent): evening digest push (D-84): once per local day at the owner's time (default 21:30), today's spend, top leaks and limits, optional skip on zero days; Settings → Spending coach; migration V36 (was tentative V42).
  - AOS-904 (integrated by the integration agent): limits (D-83): per lens, merchant or category, weekly or monthly, pushes at 50/80/100% once per threshold per period (claim ledger), a 30-day learning month and suggested limits that push only once accepted; Settings → Limits and the leaks-page panel; `NotificationType.COACH` next to AOS-620's `SECURITY`; migration V35 (was tentative V41); IdorTest rows for `/coach/limits/{id}`.
  - AOS-801 (reviewed + merged by Claude): wealth research (D-79) and Epic E8 (6 stories, AOS-802…821, ~128 h; first slice 'my mutual funds, safely' ~49 h). Built after E9 (D-80).
  - AOS-213/214/215 (reviewed + merged): SMS parsing fixes in one shared `SmsSignals` (D-119, D-120): promised credits ignored clause by clause; `Dr.`/`Cr.` alerts with "Avl Bal" kept; direction from account phrases and Dr/Cr, conflicts are needs-review (ignored, never guessed). 15 before/after cases in the ticket.
  - AOS-635 (reviewed + merged): `AiJson.parseObject` checks size and nesting before parsing; a validator exception or stack overflow becomes `Rejected(read_failed)`, audited; fatal JVM errors propagate.
  - AOS-622 (integrated by the integration agent, after #127): supply chain and runtime hardening (D-99): CI, Security and CodeQL on every PR as required checks, digest-pinned images, Gradle locks + SHA-256 verification (refreshed for AOS-617's webauthn), Python hash locks, least-privileged containers on segmented networks, merge-pr.sh waits for every required check. Gates green locally (API, shell suites, backups, hardened-stack smoke, parser, OSV, Trivy). **Integrator/Pragun:** turn the listed required checks on in branch protection after merge (docs/security/SUPPLY-CHAIN.md §4).
  - AOS-631 (reviewed + merged): tests that failed every day 18:30–24:00 UTC now use fixed clocks; regression tests at India midnight ±1 min.
  - AOS-902 (integrated by the integration agent): "Where it leaks" (D-82): `GET /coach/leaks` per lens and merchant (spent, orders, average order, late-night share, weekdays, change) with the drill-down `/coach/leaks/transactions`, `/leaks` page, Activity lens filters, Home card; no schema change. Rebased onto main (AOS-617/618/619/620).
  - AOS-707 (integrated onto main; awaiting review + merge): local learned categoriser (D-71), off by default (`LEARNED_CATEGORIZATION_ENABLED`): per-user TF-IDF + softmax regression, calibrated, last pipeline step on plain-Other rows only; nightly retrain + `POST /categories/model/train`; golden set moved to main sources as the prior; migration V42.
  - AOS-903 (integrated by Claude): order-receipt emails (Zepto, Blinkit, Swiggy/Instamart, Zomato, Amazon, Flipkart) parsed per sender in the Go worker, structured fields only, matched to the payment (never a second spend); hidden fees per app and month at `GET /api/v1/leaks/fees` (D-88); V38.
  - AOS-617 (reviewed + merged by Claude): passkeys (D-96): every full session needs Google **and** a passkey (Face ID); exact-origin, user-verification-required WebAuthn via Yubico java-webauthn-server; hashed single-use recovery codes; V34. **Pragun:** do the first sign-in yourself right after deploying, save the recovery codes, add a second passkey, and check Safari on the iPhone by hand.
  - AOS-620 (reviewed + merged by Claude): sign-in alerts and sessions (D-95): refresh rotation with reuse detection (AOS-121 done here), tokens end with their session (`sid`), alerts for new device / refused (masked) / copied key, `/settings/security` with sign out everywhere; V33.
  - AOS-618 (reviewed + merged by Claude): browser + sign-in hardening (D-94): nonce CSP and headers, CORS removed, app-wide `OriginCheck` CSRF (replaced AOS-704's route-level guard; one mechanism), sign-in state + PKCE (V32), exponential sign-in backoff, `TRUST_FORWARDED_FOR` off by default (AUD-03 proven live), strict JWT, generic errors, upload shape checks. Integration: AppConfig merged with AOS-616's `appUrl()`; a11y helper now waits for a settled page. **Pragun:** set `APP_URL` to the exact browser address.
  - AOS-619 (reviewed + merged by Claude): authorization attack tests (D-93): `RouteInventoryTest` fails CI on any unauthenticated route not on the reviewed allowlist; `IdorTest` (18 ID routes 404, 22 lists empty for another user); `e2e/security.spec.ts`; web guard matches public pages exactly (AUD-13).
  - AOS-901 (reviewed + merged by Claude): habit lenses (D-81): six lenses in `coach/lenses.json`, person-to-person UPI excluded, per-merchant overrides (V31), +37 brands; golden set 82.6% → 83.3%.
  - AOS-704 (reviewed + merged by Claude, D-101 as amended): per-feature AI consent (D-70), off by default, enforced in the gateway before anything is sent; Settings → AI features with what is sent / never sent, local vs cloud, eval and spend; same-origin guard (`SameOriginMutations`) on its mutations; V30. Story S7.1 (AI foundation) done. **Process (Pragun):** no separate reviewer agents; Claude reviews (code + security) and merges.
  - AOS-703 (AI-foundation agent, reviewed + merged): local models (D-69): opt-in `ollama` compose profile on 127.0.0.1 only, `./gradlew -q aiBakeOff` through the AI port; qwen3:4b 89.9% (rules 82.6%, recorded Claude 91.7%), gemma3:4b 89.2%. Switch with `AI_CATEGORIZATION_PROVIDER=ollama`.
  - AOS-621 (infra-security agent, reviewed + merged): encrypted backups (D-98): nightly `pg_dump | age` (public key only on the host) to a separate bucket-locked R2 bucket, checksums + encrypted manifest, `scripts/backup/restore-drill.sh`; guide `docs/guides/BACKUPS.md`. Pragun: bucket, tokens, age key offline, first drill.
  - AOS-702 (AI-foundation agent, reviewed + merged): AI layer (D-68): one `AiGateway` over an `AiProvider` port (Anthropic SDK, Ollama, fake); redaction (`PiiMasker`), circuit breaker, monthly cost cap that fails closed (`AI_MONTHLY_CAP_USD`, default $2), audit `ai_calls` with no content (V29), per-feature flags, committed eval records gate start-up; AOS-512 moved onto it; the unused Go Claude client and handlers removed. Added in review: Ollama plain http only on loopback/compose. Unblocks E7 wave 2.
  - AOS-013: **code + security review on every PR** (D-101): `scripts/review-status.sh` sets `review/code` / `review/security` on the head commit; `scripts/merge-pr.sh` refuses without both (7 shell tests). **Found:** no CI ran on PRs (only GitGuardian) and `main` had no branch protection; CI is in AOS-622; both reviewers blocked the first version (8 findings: unpinned merge, verdict on an unreviewed commit, forgeable status, PR-controlled gate…), all fixed. **Turned on (Pragun: yes):** branch protection on `main` (GitGuardian + both reviews required, admins included, no force-push), secret scanning + push protection, Dependabot alerts. Run the merge script from main: `git show origin/main:scripts/merge-pr.sh | bash -s -- N`.
  - AOS-013: **code + security review on every PR** (D-101): `scripts/review-status.sh` sets `review/code` / `review/security` on the reviewed commit; `scripts/merge-pr.sh` refuses without both, owner-created, and pins the merge (17 + 9 shell tests). **Found:** no CI ran on PRs (only GitGuardian) and `main` had no branch protection; CI is in AOS-622; both reviewers blocked the first version (8 findings: unpinned merge, verdict on an unreviewed commit, forgeable status, PR-controlled gate…), all fixed. **Turned on (Pragun: yes):** branch protection on `main` (GitGuardian + both reviews required, admins included, no force-push), secret scanning + push protection, Dependabot alerts. Run the merge script from main: `bash <(git show origin/main:scripts/merge-pr.sh) N`.
  - AOS-616 (infra-security agent, reviewed + merged): private network only (D-97): guide `docs/guides/PRIVATE-ACCESS.md`, Tailscale sidecar overlay, optional webhook-only Cloudflare Tunnel for Gmail push (off by default), `APP_URL` https-only except loopback, `scripts/check-compose-exposure.sh`. **Found in review:** the tunnel would have sent chunked bodies the API now refuses: fixed. Pragun at go-live: install Tailscale on Mac + iPhone, follow the guide.
  - AOS-615 (red-team agent, reviewed + merged): threat model + audit (ASVS 5.0 L2 / WSTG), 27 findings (1 High, 11 Medium, 15 Low; 0 Critical). Fixed: **pre-auth request-body exhaustion** (AUD-01; my review found and closed a `Transfer-Encoding` bypass of that fix) and Jackson advisories (AUD-02). In progress with the security agents: XFF trust, access-token revocation, refresh rotation, sign-in state/PKCE, headers, CSRF/Origin, passkeys, alerts, Redis auth. New: AOS-624…630. **Pragun to accept (D-91):** the web route guard is a navigation aid only (the API authenticates every call); a captured Pub/Sub push token is valid ~1 h by Google's design (AOS-630 bounds it).
- **2026-10-17:**
  - AOS-012: **security gate for every feature** (D-100): ENGINEERING §4a, PR template, CLAUDE.md rule; all running agents told to apply it.
  - **AOS-614 (critical, fixed):** any Google account with a verified email could sign in and became OWNER. Now only `OWNER_EMAIL` (verified, pinned to its Google ID, fail closed; required with a database) can sign in; others get 403 "This AdminOS is private"; non-owner sessions are revoked on refresh. **Security is P0 (D-91):** story S6.4 Owner-only fortress (AOS-614…623: red-team audit, private network, passkeys, headers, authorization attack tests, sign-in alerts, encrypted backups, supply chain, pen-test). Also fixed a polling race in `e2e/gmail.spec.ts`. **Pragun: set `OWNER_EMAIL` in `.env`.**
  - AOS-011: north star "spend less → save more → invest well" (D-80); Epic E9 Spending coach planned (S9.1–S9.4, AOS-901…910, 47h) and prioritised before the E8 build. Go-live with real data in 1–2 days (Pragun).
  - AOS-010: re-scoped (D-78): always personal, single-user, secured end to end; wealth in scope (all holdings, net worth, guidance "policy + AI explanations"); BRD v3, CLAUDE.md; E6 renamed Hardening, public items parked (⏸). AOS-801 wealth research + Epic E8 started in a background agent.
  - Pragun accepted D-67 (AI ground rules) and the AOS-512 choices ("start all with best decision long term"). E7 wave 1 started in four background agents: AOS-702→703→704 (AI layer, local models, consent), 707→708 (learned categoriser, Review queue), 713→715→709 (anomaly baselines, price rise, merchant grouping), 714→718 (forecast, cash from text). Reserved: D-68…D-77; migrations are renumbered by the integrator at merge (next free V29).
  - AOS-512 (background agent, reviewed + merged): AI fallback categorizer for "Other" (D-62), **off by default** (`AI_CATEGORIZATION_ENABLED`, `ANTHROPIC_API_KEY`, model `claude-haiku-5-5`): privacy gate sends only business names (never people, UPI IDs, amounts, text); strict JSON schema; ≥ 0.7 confidence sets the category (`ai`), else stays Other with the guess in metadata; `ai-categorization` job every 15 min (now 10 jobs), per-merchant cache (V28), circuit breaker. On the golden set, from **hand-recorded** answers: accuracy 82.6% → 91.7%, coverage 69.4% → 79.2%. API unit 1,497 / integration 125. **Next:** one real run (`ANTHROPIC_API_KEY=… ./gradlew -q categorizationReport -PrecordAi`) to measure Claude itself; web UI for AI suggestions; Go workers still use `CLAUDE_API_KEY` with an old model id (unify, with AOS-702).
  - AOS-511 (background agent, reviewed + merged): categorization evaluation harness (D-61): 288 synthetic rows through the real pipeline steps, `./gradlew -q categorizationReport`, regression gate against `baseline.json`. Baseline: accuracy 82.6%, coverage 69.4%, macro-F1 0.827 (worst: Entertainment, Other, EMI). **Fixed:** `MerchantNormalizer` read a UPI provider as a brand (`annapoorna@paytm` → "Paytm"). **Found, not fixed:** a correction keyed on a brand name relabels all of it (EMI on Amazon → every Amazon purchase EMI); surnames matching brand aliases; brands glued into UPI handles; NACH lenders; "IB BILLPAY DR-HDFC CARD"; no Rent/Income category.
  - AOS-406 (background agent, reviewed + merged): Connect Gmail (D-64): `/connections/gmail/callback`, Settings Connect / Reconnect through Google (fixed: Reconnect posted no code, always CONN_006), single-use OAuth state stored as a hash + PKCE; V27. **Found in review:** the agent's new `gmail.spec.ts` had never run and failed on every browser (Next's route announcer is `role="alert"`; WebKit refuses a mocked 302): fixed. API unit 1,409 / integration 123; web 358. Pragun: add `<APP_URL>/connections/gmail/callback` as a redirect URI on the Google OAuth client (AOS-111).
  - AOS-405 (background agent, reviewed + merged): Gmail push (D-63): the API registers `users.watch` after connect, `gmail-watch-renewal` every 6 h, `users.stop` on disconnect; V26 watch columns; every push is incremental (fixed: a push without a history ID ran a full sync). After rebase on AOS-403: API unit 1,387 / integration 120, Kover gate. **Reported, not fixed:** `ConnectionRepository.update` writes every column, so connect/disconnect racing a worker sync can rewind `history_id`; push bursts aren't coalesced (the worker never marks a session `in_progress`). Pragun (with AOS-111): Pub/Sub topic + OIDC push subscription to `/api/v1/webhooks/gmail`, Publisher role for `gmail-api-push@system.gserviceaccount.com`, `GOOGLE_GMAIL_PUBSUB_TOPIC`.
  - AOS-403: WhatsApp chat export import (D-66): `.txt`/`.zip` (iPhone + Android formats, multi-line, day/month detection, user-zone times), bank senders only by display name, same SMS rules via `SmsParserRegistry.parseFromBank`, idempotent fingerprints, 10 MB text cap also in zips; `POST /api/v1/imports/whatsapp`; web section on Import. API unit 1,331 / integration 116; web 334 (coverage 98.4% lines); Playwright 250 + 7 skips (one unrelated flake, known issue 16). Started in a background agent: AOS-701 AI/ML research and Epic E7.
  - AOS-701 (docs only, background agent): AI/ML research → `docs/research/AI-ML-RESEARCH.md`, new Epic E7 "Smart features" (stories S7.1–S7.5, planned tasks AOS-702…719, 115h), D-67 AI ground rules proposed (no invented numbers, validated outputs, off by default with minimum data, local-first, one provider port, per-feature eval gate). Recommended first: no-LLM wave (AOS-707 local categoriser, AOS-713 robust anomaly baselines, AOS-708 Review queue, AOS-714 forecast), then AOS-702 AI layer after AOS-512. **Found (not fixed):** Go `internal/claude` client defaults to `claude-sonnet-4-20250514` and its briefing/anomaly-explain handlers are registered but no longer enqueued by the API. **Next:** Pragun to accept or amend D-67 and pick the first E7 task.
  - AOS-513: subscription detection fixes (D-65) + honest "how to cancel" (dialog with steps, via the mandate when one pays it); review logic moved into `SubscriptionReviewService`. API unit 1,312 / integration 116; web 330; Playwright 247 + 7 skips. In parallel: background agents on AOS-511/512 (categorization eval + AI fallback) and AOS-403/404 (Gmail watch + Connect Gmail).
  - AOS-401 (background agent, reviewed + merged): Gmail sync (D-59): real history IDs, filtered 30-day fallback, financial senders only, full body in memory, Go reads/writes TokenEncryptor v2 (shared vector), connect flow checks the grant, manual sync really queued, daily `gmail-fallback-sync` with 24 h skip rule. Fixed 7 latent bugs (e.g. the old cron would have disconnected every connection with an expired access token). Follow-ups: `users.watch`, web Connect Gmail with OAuth state.
  - AOS-507: documents vault (D-60): envelope encryption in the app (per-file AES-256-GCM, key wrapped with the app key, SHA-256 checked), Cloudflare R2 / S3 adapter (AWS SDK, URL-connection client), owner-only API, expiry reminders, web /documents with Undo delete; V23. **Found:** over-limit multipart uploads were 500s (statements too) — now 413 globally. MinIO images gone → tests use Adobe S3Mock. API unit 1,283 / integration 114; web 330, Playwright 247 + 7 skips.
  - AOS-509 (background agent, reviewed + merged): waste score (D-58), V24; follow-ups reported: detector always saves category OTHER, never ends stopped subscriptions, uses `!!` and UTC dates; SubscriptionAgentRoutes calls the repository from a route; cancel route still a stub.
  - AOS-506: weekly briefing (D-57): deterministic, from analytics + dues + anomalies + flagged subs + dormant mandates; user's day/time/zone; once per week with catch-up; BRIEFING notification; `formatInr()`; queue payloads typed. API unit 1,229 / integration 108.
  - AOS-502: mandates (D-56): UPI AutoPay / e-NACH / card SI notices from SMS (set-up, pre-debit, pause, resume, revoke), robust to late and repeated messages; debits matched after commit and marked recurring (fills `is_recurring`); dormant flagged; `GET /api/v1/mandates`; Subscriptions section; dues calendar AutoPay items. **Found by tests:** lenient dates (31/02 → 28 Feb), stop-word payees, UMN trailing dot. V22. API unit 1,222 / integration 106; web 318, Playwright 232 + 7 skips.
  - AOS-503: dues & renewals (D-55): `GET /api/v1/dues` (bills + projected renewals + overdue, totals), web `/bills` month view (URL `?m=`); renewal reminders; reminders on the user's own date (`UserCalendar`); reminder days stored (V21); look-ahead 30 days; Gmail bills: ISO day-first dates, skipped without one, type + card last 4. API unit 1,195 / integration 105; Go unit + integration (throwaway Postgres); web 314 unit, Playwright 229 + 7 skips.
  - AOS-508: bill-payment matching (D-54): pure `BillPaymentMatcher` (amount full/minimum, window, evidence, ranking, ties → nothing), listener on each new transaction, daily sweep before reminders; card bills paid by transfer now match. **Found:** Postgres bill update dropped `metadata` (fixed). Unit 1,177, integration 104.
  - AOS-505: unusual-charge alerts (D-53): `TransactionCreatedListener` hook after the commit; detector judges each spend against its own past (30-day average with ≥3 spends and ₹2,000 minimum, 24 h duplicates incl. same minute, user-zone odd hours); one anomaly per transaction (V20); alert only if confidence ≥ 0.7 and from the last 3 days. Unit 1,162, integration 102.
  - AOS-510: Web Push (D-52): RFC 8291 encryption + RFC 8292 VAPID with JDK crypto (RFC test vector passes), push-service allowlist (no SSRF), `/api/v1/push` routes, V19, `./gradlew -q vapidKeys`; web service worker, manifest + icons, Settings → Notifications on this device. **Found by tests:** the route guard would have blocked the manifest and service worker without a session. API unit 1,146 / integration 101; web 310 unit, Playwright 226 + 7 skips.
  - AOS-504: notification delivery (D-51): `DeliveryChannel` plug-ins (in-app, SMTP email), dispatcher with backoff 5m/30m/3h then failed, quiet-hours hold until the user's local end, pending job every 5 min with lease claims, V18. FCM stub removed. **Found by tests:** static `Transport.send` ignored TLS settings. Unit 1,110, integration 100.
  - AOS-501: scheduler (D-50): `domain/scheduling` (Schedule, ScheduledJob, JobScheduler, claim per (job, slot) in V17 `job_runs`, retries, timeouts, shutdown-safe); jobs: bill reminders 09:00, nudges 11:00, subscription detection 04:00 IST. Unit 1,101, integration 99; 99.6% lines / 95.3% branches.
  - AOS-309: Review page (tabs in the URL; both sides, reason, score; plain-language answers; Undo for 5 s before sending; send on leave; error → card returns). `UndoToast` timer runs once per message. Web 283 unit (98.49% / 95.85%), Playwright 217 + 4 skips. **Milestone 3 code complete.**
  - AOS-307: Activity feed (filters in URL, batch apply, chips, day groups, edit sheet `?txn=`, re-categorize + Undo); API `nature` filter; **fixed feed paging that skipped same-timestamp rows** (cursor = time + id).
  - AOS-306: Spending → Trends: 12 months spent vs received (bars/lines in the URL) and 12 weeks.
  - AOS-305: Spending (`/spending` categories | merchants in the URL; `/spending/category/{cat}` with 6-month trend, merchants, transactions); analytics `category` filter.
  - AOS-304: month view on Home: running-total line (this month vs last month, today marked, sentence + table) and day-by-day columns. Web 244 unit, 174 Playwright.
  - AOS-303: console Home (period in URL; headline vs the same days of the previous period; review banner; week bars (Recharts, table toggle); top categories/merchants; latest; bills due soon; anomalies). Web 238 unit, 168 Playwright.
  - AOS-302: console design spec (`docs/design/console.md`, D-49) + foundation: tokens, `lib/format.ts`, `lib/period.ts` (period in the URL), 5-tab TabBar / desktop Rail, More page, PeriodSwitcher, Recharts. Web 231 unit, 150 Playwright.
  - AOS-613: reminders and nudges send once (de-duplicated days, cap by nudge day, no same-day repeat); Gmail connect needs a real OAuth grant (400 otherwise, Google rejection = 400 CONN_002); reconnect reactivates the row. All 19 AOS-312 bug tests now pass.
  - AOS-612: bills page by (due date, id) so same-day bills aren't skipped; refunds can't pay bills; ₹0 bills match; dashboard greeting and due-day counts use the user's timezone.
  - AOS-611: every API money field is an exact decimal string (D-30) + transactions carry `nature`; web uses `lib/money.ts` (BigInt paise, Indian grouping); feed labels refunds/reversals/transfers.
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
