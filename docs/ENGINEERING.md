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
| 5 | **Dependencies** | `infrastructure/*` clients (Google, R2, queue, Claude) | `internal/claude`, `internal/gmail` | Talk to external services | Leak external request/response models upward |

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

### Rules
- **One behaviour per test.** Name tests as sentences in backticks, e.g.
  `` `refund within 30 days links to original debit` ``. Structure the body as given / when / then.
- Assert errors with `assertFailsWith<…>` (Kotlin) or `require.ErrorIs` / `errors.Is` (Go).
- Don't mock what you own: use the `InMemory*` fakes. Mock only external clients.
- Tests are deterministic: inject `Clock` and ID generators; no sleeps, no real network.
- **Every bug fix ships with a regression test** that fails before the fix.
- **Every acceptance criterion in a ticket maps to at least one test** (or a documented manual check).
- Test data uses realistic Indian bank formats (HDFC/SBI/ICICI SMS, UPI VPAs, ₹ amounts).

---

## 4. Definition of Done (every task)

- [ ] Acceptance criteria met; each covered by a test or a documented manual check.
- [ ] `./gradlew test` (and `go test ./...` / `next build` where touched) green; integration tests green when the DB is touched.
- [ ] Layer rules respected; no new silent fallbacks; no secrets or PII in logs.
- [ ] Decisions recorded in DECISIONS.md (with type).
- [ ] Ticket closed: Resolution, Actual, Done date; story/epic/board rows updated.
- [ ] PROGRESS.md updated.
- [ ] Commit message starts with the task ID: `AOS-xxx: …`.
