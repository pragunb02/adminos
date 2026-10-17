# Engineering Standards

_How we write code in AdminOS. Decision D-26 · Ticket AOS-006 · Last updated 2026-10-16._

Apply these standards to **new code** and to **any code a ticket touches**. Gaps in existing
code are tracked as tickets (AOS-007); don't rewrite untouched code opportunistically.
Patterns guide design but don't replace thinking. When breaking a rule is the right call,
say why in the ticket.

---

## 1. Architecture: five layers, three rules

### Layers (top to bottom)

| # | Layer | Kotlin API | Go workers | Responsibility | Must not |
|---|---|---|---|---|---|
| 1 | **API layer** | `domain/<x>/*Routes.kt` + request/response DTOs | `handlers/*` `Parse` step | HTTP/queue I/O, auth, input shape validation, DTO ↔ domain mapping | Contain business rules; pass DTOs downward |
| 2 | **Domain logic** | `domain/<x>/*Service.kt` | service structs/functions | Business rules: categorization, dedup, transfers, refunds, detection | Touch the DB, HTTP, queue or DTOs directly |
| 3 | **Domain data** | repository/accessor interfaces in `domain/<x>` | store interfaces | Provide domain objects regardless of source (DB, cache, external); aggregate sources | Make business decisions |
| 4 | **Persistence** | `infrastructure/database/repositories/Exposed*` + `tables/*` | `pgx` store implementations | CRUD on data we own; row ↔ domain mapping | Leak rows/Table types upward |
| 5 | **Dependencies** | `infrastructure/*` clients (Google, R2, queue, AI providers) | `internal/gmail` | Talk to external services | Leak external request/response models upward |

Layers 4 and 5 sit side by side under layer 3.

### Rules
1. **Adjacent layers only.** Routes call services; services call repositories/accessors;
   only those call persistence or clients. Routes never call a repository directly.
2. **Calls go downward.** A lower layer never calls an upper layer. Use return values or
   events instead of callbacks into services.
3. **Request/response.** Every interaction is a call with a result: a direct call, a
   suspend function, or queued work whose result comes back later.

### Object types per layer
| Type | Defined in | Visible to |
|---|---|---|
| API DTOs (`*Request`, `*Response`, `@Serializable`) | API layer | API layer only |
| Domain models (`Transaction`, `Money`, `UserId`) | domain package | Layers 1–3 (domain models rarely change) |
| Persistence rows / `Table` objects | persistence | Persistence only |
| External models (Google, Claude, R2 payloads) | dependency clients | That client only |

### Structure inside a layer
- Behaviour lives in **stateless** classes with dependencies injected (Koin).
- Data crosses layers as **immutable data classes**.
- Group cohesive classes into one package per domain (`identity`, `ingestion`, `financial`, `agent`, `notifications`).
- Packages for different domains don't reach into each other's internals; they go through
  that domain's service.
- SOLID still applies inside a layer.

---

## 1a. API contract (D-31)
- JSON fields and query params are **camelCase**. External services keep their names via `@SerialName`.
- Parse query params only with `QueryParams` helpers (`enumParam`, `booleanParam`, `rangeStartParam`…). Bad input → 400 with allowed values.
- Pagination cursors are opaque (`CursorCodec`). Never return or accept raw sort keys.
- Money in new DTOs is exact (string or BigDecimal-backed), never `Double` (existing DTOs: AOS-301).
- Undecodable request bodies are 400 `VALIDATION_004`. Domain models validate themselves in `init` (`require`), so invalid objects can't exist.
- Raw message text (SMS/WhatsApp/email bodies) is never stored or logged (D-33).

## 2. Coding rules

### Typed values, not strings
- IDs and constrained values get their own type:
  ```kotlin
  @JvmInline value class UserId(val value: UUID)
  @JvmInline value class AccountLast4(val value: String) { init { require(value.matches(Regex("\\d{4}"))) } }
  ```
- **Money** is `Money(paise: Long, currency: Currency)` or `BigDecimal` with scale 2. Never `Double`/`float64`.
- Closed sets are enums or sealed classes, not strings. Map them to and from DB/JSON strings in one place.

### Nulls
- Never use `!!`.
- Absent-but-valid uses a nullable type the caller must handle.
- Failure **throws** (or returns a sealed `Result`); never return `null` to signal an error.
- Collections are never null; use `emptyList()`.
- Validate constructor and public-function inputs with `require(...)` so bad data fails at the boundary.

### Fail fast, no silent fallbacks
- Missing required input → 4xx at the API layer.
- Missing required config → the app refuses to start (outside explicit `dev` mode).
- Don't invent defaults for client data. Two existing anti-examples:
  - the fake `"user@gmail.com"` connection
  - the storage stub that silently drops uploads
- A fallback is allowed only if it's a documented requirement, and it must be logged and visible.

### Immutability
- `val` over `var`.
- Data classes with `copy(...)` instead of mutation.
- Expose `List`/`Map`, never `MutableList`.

### Function signatures
- At most about 4 parameters. Beyond that, use a parameter object (a data class).
- Avoid adjacent parameters of the same type (`from: Instant, to: Instant`). Use a type such
  as `DateRange`, or named arguments.

### Interfaces
- Create an interface only when there are **2 or more implementations**, e.g. `InMemory*`
  and `Exposed*` repositories, or `Stub`/`R2` storage.
- Otherwise use the class directly.

### Logging
- Use placeholders: `log.info("Synced {} txns for {}", count, userId)`. No string concatenation.
- Pass the exception as the last argument: `log.error("Sync failed for {}", userId, e)`.
- **Never log:** SMS/email bodies, OAuth/JWT tokens, full account or card numbers, amounts
  tied to identity in error messages, or Claude prompts that contain user data.

### Errors
- Wrap with the cause: `throw IngestionException("PDF parse failed", e)`. Go: `fmt.Errorf("parse pdf: %w", err)`.
- Catch the narrowest type you can handle. No `catch (e: Exception)` except at the outermost
  boundary (route status pages, the worker loop).
- Domain errors are sealed classes. Only the API layer maps them to HTTP status and error codes.

### Language specifics
- **Kotlin:**
  - Coroutines over threads.
  - No blocking calls on request threads; wrap with `withContext(Dispatchers.IO)`.
  - `@SerialName` for every JSON field whose name differs from the property.
- **Go:**
  - Accept interfaces, return structs.
  - `context.Context` is the first parameter.
  - No `panic` in handlers.
  - Map iteration order is random: never depend on it (sort, or use slices).
- **TypeScript/React:**
  - All API calls go through `src/lib/api.ts` with typed request/response shapes.
  - Components don't build URLs or parse errors.
  - No `any`.

---

## 3. Testing standards

| Level | What | Tools | Where |
|---|---|---|---|
| **Unit** | Every domain service and parser; layers 1–3 with in-memory fakes | `kotlin.test` + JUnit 5; Go `testing` (table-driven) | `services/api/src/test`, `*_test.go` |
| **Property** | Parsers and normalizers (SMS, statements, merchants, dedup fingerprint) | jqwik; Go `testing/quick` or `rapid` | same |
| **Integration** | Every `Exposed*` repository round-trip on real Postgres; queue publisher | Testcontainers (`@Tag("integration")`) | `infrastructure/...IntegrationTest.kt` |
| **Contract** | Kotlin publisher payload ↔ Go handler payload; web ↔ API JSON | shared JSON fixtures in `contracts/` | both sides test the same fixture |
| **Route** | Status codes, auth, validation, error mapping | Ktor `testApplication` | `*RoutesTest.kt` |
| **Web unit/component** (D-47) | Every helper, component and page: loading, empty, error + retry, data; every action's success and failure | Vitest + Testing Library + user-event + MSW (unmocked requests fail the test) | `apps/web/src/**/*.test.tsx`; fixtures in `apps/web/test/` |
| **Web end-to-end** (D-47, AOS-311) | Real browser: navigation, back/forward, deep links, URL state, keyboard, a11y, mobile + desktop | Playwright (mocked API + full stack) | `apps/web/e2e/` |

### Rules
- **One behaviour per test.** Name tests as sentences in backticks, e.g.
  `` `refund within 30 days links to original debit` ``. Structure the body as given / when / then.
- Assert errors with `assertFailsWith<…>` (Kotlin) or `require.ErrorIs` / `errors.Is` (Go).
- Test functions must return `Unit`: write `= runBlocking<Unit> { … }`. JUnit silently skips test methods that return a value; `TestConventionsTest` enforces this.
- Persisted JSON (`Map<String, Any?>`) always goes through `JsonColumns` (D-30). Never call `serializer()` on `Any`.
- Don't mock what you own: use the `InMemory*` fakes. Mock only external clients.
- Tests are deterministic: inject `Clock` and ID generators; no sleeps, no real network.
- **Every bug fix ships with a regression test** that fails before the fix.
- **Every acceptance criterion in a ticket maps to at least one test** (or a documented manual check).
- Test data uses realistic Indian bank formats (HDFC/SBI/ICICI SMS, UPI VPAs, ₹ amounts).
- **API coverage gate (D-48):** `./gradlew test integrationTest koverVerify` fails below 90% lines / 85% branches over the whole API (unit + integration). A new bug found by a test may be committed as `@Disabled("BUG <ticket>: …")` only together with its fix ticket; the fix enables it.
- **Web coverage gate (D-47):** `npm run test:coverage` fails below 90% lines/statements and 85% branches/functions over `apps/web/src`.
- Web tests find elements by role and accessible name (what a user or screen reader sees), not by CSS classes. An input without a label is a bug to fix.

---

## 4. Definition of Done (every task)

- [ ] Acceptance criteria met; each covered by a test or a documented manual check.
- [ ] `./gradlew test` (and `go test ./...` / `next build` where touched) green; integration tests green when the DB is touched.
- [ ] Layer rules respected; no new silent fallbacks; no secrets or PII in logs.
- [ ] Decisions recorded in DECISIONS.md (with type).
- [ ] Ticket closed: Resolution, Actual, Done date; story/epic/board rows updated.
- [ ] PROGRESS.md updated.
- [ ] Commit message starts with the task ID: `AOS-xxx: …`.
- [ ] **Security gate passed (§4a).** No feature ships without it (D-91, D-100).
- [ ] **Code and security reviews passed (§4b)** on the final commit (D-101).

## 4a. Security gate (every feature, every time; D-100)

Security is P0 (D-91). A task that adds or changes behaviour is not done, and its PR is not merged, until every item below holds. A security finding blocks the merge until it is fixed or Pragun explicitly accepts it.

**1. Threat review (in the ticket, a "Security" section):**
- [ ] What the change exposes: new routes, inputs, uploads, outbound calls, stored data, secrets, jobs, dependencies.
- [ ] How an attacker would abuse it (stranger on the internet, a malicious email/SMS/file, a stolen session, another user's IDs), and what stops each.

**2. Tests that attack it (all three levels where they apply):**
- [ ] **Unit:** hostile input (oversized, malformed, injection strings, path tricks, unicode tricks), and fail-closed behaviour on every error path.
- [ ] **Integration/route:** the route requires auth (the route-inventory test, AOS-619, must pass with no new allowlist entry, or a reviewed one); another user's resource is 404; mutations reject missing CSRF/Origin; limits and rate limits hold.
- [ ] **Playwright:** signed out → redirected; a forged/expired token → refused; the new screen renders untrusted text safely (no HTML injection); no secrets or tokens in the DOM, storage or bundles.

**3. Checks:**
- [ ] No secrets, tokens, personal data or raw message text in code, fixtures, logs or errors (D-33); GitGuardian green on every commit.
- [ ] No new public listener: published ports stay `127.0.0.1`; nothing widens a bind (D-91).
- [ ] New dependencies: licence checked, latest stable, no known CVE (dependency scan green, AOS-622).
- [ ] Data at rest: sensitive new fields encrypted or justified in the ticket.
- [ ] AI features: off by default, minimum data, outputs validated (D-67).

**4. Review:** the reviewer (the integrator) re-reads the diff as an attacker before merging and runs the full Playwright suite, including `e2e/security.spec.ts`.

## 4b. Review step (every PR, before merge; D-101)

Every PR is reviewed by **two independent reviewers** after its last push and before merge: a **code reviewer** and a **security reviewer**. Each is a separate reviewer agent that did not write the change, working from the PR diff, the ticket and these standards.

| Reviewer | Looks for | Tooling |
|---|---|---|
| **Code** | Correctness bugs and edge cases; layer rules (§1); coding rules (§2); tests that really test the acceptance criteria (§3); simpler or reused code | `/code-review` |
| **Security** | The §4a gate: auth and ownership on every new route, injection, XSS, SSRF, upload abuse, secrets and PII, exposure, crypto misuse, dependency risk; attack tests present | `/security-review` + the threat model (`docs/security/THREAT-MODEL.md`) |

- **Verdict:** each reviewer posts its findings as a PR comment and sets the commit status `review/code` or `review/security` **on the exact commit it reviewed**: `scripts/review-status.sh <pr> <kind> <success|failure> <reviewed-sha> <findings-file>`. The script refuses if the head has moved since.
- **Comments are public:** findings never quote secrets, tokens, personal data or financial data. Describe them and point to file:line.
- **Blocking:** any correctness bug, any security finding (D-91), missing tests for an acceptance criterion, or a broken layer rule. Any change to `scripts/` or `.github/` gets a line-by-line security review, because those files are the gate itself. Style nits don't block; they are listed.
- **Fix and repeat:** fixes are pushed, and both reviews run again. A status belongs to one commit, so a new push always needs a fresh review.
- **What enforces it:**
  - **GitHub branch protection on `main`** (admins included) requires `GitGuardian Security Checks`, `review/code` and `review/security` on the head commit, an up-to-date branch, and no force-push.
  - **`scripts/merge-pr.sh`**, run **from `main`'s copy** so a PR can't change its own gate: `f=$(mktemp) && git fetch origin && git show origin/main:scripts/merge-pr.sh > "$f" && bash "$f" <pr>; rm -f "$f"` (the merge happened only if it prints `MERGED #<pr>`). It also refuses unless every check run succeeded, the newest review status per context is `success` **and was created by the owner's account** (not an Actions workflow), and it pins the merge to the reviewed commit (`--match-head-commit`). Tested in `scripts/tests/merge-pr.test.sh` and `scripts/tests/review-status.test.sh`.
  - **Honest limits:**
    - the review statuses are set with the owner's own token, so they show that the process ran, not who reviewed;
    - GitHub's branch protection can't check *who* set a status: only the merge script refuses a status not created by the owner. So merges go through the script, never the web UI. A workflow that writes statuses is a blocking item for the security reviewer.
    - The control is the process plus these guards, not a cryptographic proof.
- The integrator still runs the full gates and Playwright (§4, §4a) and reads the reviews before merging.

---

## 5. Design principles

| Principle | What it means here |
|---|---|
| **Single responsibility** | One reason to change per class: a parser parses; it doesn't save or notify |
| **Open/closed** | New bank, source, rule or category = **new class registered**, never an edit to the engine |
| **Liskov substitution** | Every `InMemory*` fake behaves like its `Exposed*` implementation (same tests run against both) |
| **Interface segregation** | Small role interfaces (`TransactionReader`, `TransactionWriter`) instead of god-interfaces |
| **Dependency inversion** | Domain code depends on interfaces it owns; infrastructure implements them; Koin wires them |
| **DRY / single source of truth** | One categorizer, one normalizer, one enum mapping; no Kotlin/Go duplicates of the same rule |
| **KISS / YAGNI** | Build what the ticket needs, but behind seams that let it grow |
| **Composition over inheritance** | Assemble behaviour from small parts (pipeline steps, rules); avoid deep class trees |
| **Explicit over implicit** | No magic defaults, no hidden global state; configuration is typed and validated at startup |
| **Privacy by design** | Minimize data collected; raw messages filtered at the edge; secrets encrypted |

## 6. Design patterns (and where we use them)

| Need | Pattern | In AdminOS |
|---|---|---|
| Many input sources | **Adapter** → one `IngestEvent` (D-21) | `ShortcutSmsAdapter`, `GmailAdapter`, `StatementAdapter`, `WhatsAppAdapter` |
| Bank/format-specific parsing | **Strategy** + **Registry** | `SmsParser` per bank (`HdfcSmsParser`…) chosen by TRAI sender header; `StatementParser` per bank/format |
| Accuracy engine | **Pipeline / Chain of Responsibility** | `Normalize → Dedup → SelfTransfer → RefundLink → Categorize`; each step is a pure, separately tested `IngestStep` |
| Categorization and anomaly rules | **Specification / Rule objects** | `MerchantRule`, `KeywordRule`, `UserOverrideRule`; `LargeAmountRule`, `CardTestingRule`; ordered by priority |
| Data access | **Repository** | Existing `*Repository` interfaces with InMemory + Exposed implementations |
| DB write + enqueue atomically | **Transactional Outbox** | Write the event and an `outbox` row in one transaction; a relay publishes to Redis (no lost jobs) |
| React to changes | **Domain events / Observer** | `TransactionIngested`, `BillDetected`, consumed by detectors and notifiers |
| Worker job flow | **Template Method** | The existing Parse → Execute → Persist → Notify workflow in Go |
| Object creation across layers | **Factory / Mapper** | One mapper per boundary (`TransactionDtoMapper`, `TransactionRowMapper`) |
| Optional external services | **Null Object only in explicit dev mode** | e.g. `DevStorageClient`, which logs loudly; in prod mode missing config fails startup |
| Retries / external calls | **Retry with backoff + Circuit breaker** | Claude, Gmail, WhatsApp clients |

### Design before code
- Every **story** and every **non-trivial task** (new module, new pattern, schema change,
  cross-service contract) fills in the **Design** section of its ticket before coding:
  - context
  - options considered (at least 2)
  - choice and the pattern(s) used
  - how it extends
  - impact on other layers and services
- Pragun approves the design. Significant choices become a decision (type: Architecture).
- Diagrams: Mermaid in the ticket when the flow spans more than 2 components.

## 7. Dependencies & versions

- **Latest stable only:** use the newest **GA/stable** release. No alpha, beta, RC,
  milestone, EAP, SNAPSHOT or nightly builds.
- **Supported combinations:** when the latest versions of two libraries don't work together
  (e.g. a framework not yet supporting the newest TypeScript major), use the newest
  **officially supported** combination and note it in the ticket.
- **Runtimes:** use the latest **LTS** (JDK, Node.js) or the latest stable (Go, Python) that
  all our libraries support.
- **Pin exact versions:**
  - Gradle: version catalog / `gradle.properties`
  - npm: exact versions plus a committed lockfile
  - Python: `==` pins
  - Go: `go.mod` / `go.sum`
  - Docker: explicit tags, never `latest`
- **Upgrade cadence:** check monthly (or when a ticket touches the area) with
  `./gradlew dependencyUpdates`, `npm outdated`, `go list -m -u all`, `pip list --outdated`.
  - Security fixes are applied immediately.
  - Major upgrades get their own ticket and are done one service at a time, with green tests before and after.
- **Adding a dependency:**
  - It must be actively maintained, with a release in the last 12 months.
  - Its license must be compatible (MIT/Apache/BSD).
  - Prefer the standard library or an existing dependency.
  - Record why in the ticket.
