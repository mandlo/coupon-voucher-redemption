# Coupon/Voucher Redemption — Persistence, Service & API Layers

A coupon/voucher redemption system built with **Java 21**, **Spring Boot 3**, **Maven**, **PostgreSQL**, and **springdoc-openapi (Swagger)**.

This repository contains the **persistence layer** (JPA entities, Spring Data repositories, Flyway schema migrations), the **service layer** (`CouponService`, orchestrating the redeem-a-coupon operation and its custom exceptions) on top of it, and now the **API layer** (`CouponController`, request/response DTOs, and a global exception handler) on top of that — all covered by **unit tests only**.

## Why this domain

The whole point of this app is one specific piece of state: a coupon can be redeemed at most `maxRedemptions` times, and `redemptionCount` is a running total that climbs toward that limit. The interesting behavior isn't "can this coupon be redeemed" in general — it's the exact boundary:

- The **Nth** redemption (the last one allowed) must succeed.
- The **(N+1)th** redemption must fail, and must fail *without* changing the count — a rejected write is not a partial write.

That's more state to keep straight across a test than a plain create/read/update/delete scenario (you have to track a counter across repeated calls and assert on it after each one), but it's still sequential logic — no concurrency, no new persistence pattern. It reuses the same shape as a stock counter with an audit trail: `Coupon.redemptionCount` is the counter (compare to a `Product.stockQuantity`), and `Redemption` is the audit trail entity created each time the counter successfully moves (compare to a `StockMovement` row). The new part is entirely about counting precisely and asserting the boundary, not about a new kind of relationship or query.

## Domain model

See [`docs/erd.md`](docs/erd.md) for the full entity-relationship diagram and table-by-table notes, and [`docs/uml.md`](docs/uml.md) for the class diagram and repository interfaces.

Two tables:

- **`coupons`** — `code` (unique), `max_redemptions` (the limit, "N"), `redemption_count` (the running total, starts at 0).
- **`redemptions`** — one row per successful redemption: which `coupon_id`, who redeemed it (`redeemed_by`), and `redeemed_at`. A plain `@ManyToOne` from `redemptions` to `coupons`.

The database schema (`V1__init_schema.sql`) doesn't just trust the application to get the boundary right — it enforces the same invariant independently with a `CHECK` constraint:

```sql
CONSTRAINT chk_coupons_redemption_count_within_limit CHECK (redemption_count <= max_redemptions)
```

So even if some future code path forgot to call `Coupon.redeem()` and tried to write `redemption_count` directly, Postgres would still refuse to let it exceed the limit. This constraint isn't exercised by any test in this layer (that requires a real database — see [What's not tested here](#whats-not-tested-here)), but it's worth reading `V1__init_schema.sql` to see the boundary expressed twice, once in Java and once in SQL, independently.

## Code walkthrough

### Entities (`domain/`)

**`Coupon`** is where the counted-resource logic actually lives:

- A plain constructor (`new Coupon(code, maxRedemptions)`) validates both arguments — blank/null code and negative limits are rejected with `IllegalArgumentException` — and trims the code. No Lombok, no Bean Validation annotations: the validation is a few lines of hand-written code, which keeps the one behavior that matters (`redeem()`) in the same style as everything around it.
- `redeem()` is the single write path. It checks `isExhausted()` (`redemptionCount >= maxRedemptions`) and, if the coupon still has redemptions left, increments the counter. If it doesn't, it throws `CouponExhaustedException` and **does not touch the counter** — a failed redemption is a no-op, not a partial increment. This one method is the entire "counted-resource exhaustion" behavior the app is named for.
- `remainingRedemptions()` and `isExhausted()` are read-only helpers derived from the two stored fields — no extra state to keep in sync.
- **Equality is code-based**, not id-based: `equals`/`hashCode` compare on `code` alone, so a freshly-built `Coupon` and one that's already had `redeem()` called on it several times still compare equal if they share a code. This is what lets a test build one coupon, redeem it repeatedly, and still assert it against a fresh reference built the same way.
- A limit of `0` is allowed on purpose — it's a legitimate edge case (a coupon that's already exhausted the moment it's created), and the tests check that the very first `redeem()` call on it fails, exactly like the `(N+1)`th call would on any other coupon.

**`Redemption`** is the audit trail, and deliberately does *less*:

- It records the fact that a redemption happened — which coupon, who redeemed it, when — and nothing about whether it *should* have happened. That decision belongs entirely to `Coupon.redeem()`. `CouponService.redeemCoupon` (see [Service layer](#service-layer-service) below) is what actually calls `coupon.redeem()` and then saves a `Redemption`, as one transaction; this entity itself still only models the fact being stored, not the orchestration.
- `redeemedAt` is stamped with `Instant.now()` **inside the constructor**, not via Hibernate's `@CreationTimestamp`. That's a direct consequence of "unit tests only": `@CreationTimestamp` only populates a field when Hibernate actually persists the entity, which means testing it would require a database. Stamping the timestamp in plain Java means `new Redemption(coupon, "someone@example.com")` has a real, assertable `redeemedAt` with no database and no mocking involved.
- Like `Loan` in an earlier lesson in this series, `Redemption` does **not** override `equals()`/`hashCode()`. There's no natural single-field business key for "one redemption event" — two redemptions of the same coupon by the same person a second apart are two different, real events, not duplicates — so it falls back to identity equality, which is exactly what the tests assert (`equals_fallsBackToIdentity`).

### Repositories (`repository/`)

See [`docs/queries.md`](docs/queries.md) for a full walkthrough of every query below — what it does, why it needed the mechanism it uses, and how Spring Data resolves it. Short version:

`CouponRepository` demonstrates three query techniques side by side, on purpose, so they're easy to compare:

1. **Derived query** — `findByCode(String code)`. Spring Data generates the query from the method name; no SQL or JPQL is written anywhere.
2. **Named queries** — `findExhausted()` and `findNearExhaustion(int threshold)`. Both queries are declared once, with two `@NamedQuery` annotations (repeatable since Jakarta Persistence 2.2, no wrapping `@NamedQueries` needed), directly on the `Coupon` entity. Neither repository method has a `@Query` annotation — Spring Data finds `Coupon.findExhausted`/`Coupon.findNearExhaustion` automatically because each method name matches its query name exactly. `findExhausted` mirrors `Coupon.isExhausted()`'s own rule (`redemptionCount >= maxRedemptions`) as a query; `findNearExhaustion` is the one an ops alert would run — coupons with `threshold` or fewer redemptions left, excluding coupons born already exhausted with a limit of `0`.
3. **Native query + projection** — `findRedemptionSummaries()`. A raw SQL `LEFT JOIN` between `coupons` and `redemptions`, grouped by coupon, mapped onto the `CouponRedemptionSummary` projection interface. It returns, per coupon, both `redemptionCount` (the in-memory counter) and `actualRedemptionRows` (a real `COUNT` of persisted `Redemption` rows) side by side — the query that would catch it if the two ever drifted apart. This is native by necessity: `Coupon` holds no `@OneToMany` back to `Redemption` for JPQL to `COUNT()` over.

`RedemptionRepository` supports the audit trail rather than being the star of the show, but uses all three techniques too:

- **Derived** — `findByCouponIdOrderByRedeemedAtAsc(Long couponId)` (every redemption for a coupon, oldest first) and `countByCouponId(Long couponId)` — the literal "running count" the whole domain is about, expressed as a query: the number of `Redemption` rows that actually exist for a coupon, which should always equal `Coupon.redemptionCount`.
- **Named** — `findRecentForCoupon(Long couponId)`, resolving `Redemption.findRecentForCoupon` (newest redemptions first), declared with `@NamedQuery` on `Redemption` and traversing the `r.coupon.id` association path in JPQL.
- **Native** — `findDailyRedemptionCounts(Long couponId)`, bucketing one coupon's redemptions by calendar day with PostgreSQL's `date_trunc` function — native by necessity, since neither the function nor the day-bucket aggregate has a JPQL equivalent — mapped onto the `DailyRedemptionCount` projection.

### Schema (`src/main/resources/db/migration/`)

Flyway owns the schema — `spring.jpa.hibernate.ddl-auto` is set to `validate` in `application.yml`, so Hibernate checks the entity mappings against the migrated schema at startup and refuses to start if they disagree, but never generates DDL itself.

`V1__init_schema.sql` creates both tables, a foreign key from `redemptions.coupon_id` to `coupons.id`, an index on that foreign key, and three `CHECK` constraints: `max_redemptions >= 0`, `redemption_count >= 0`, and — the important one — `redemption_count <= max_redemptions`, the database's own copy of the invariant `Coupon.redeem()` enforces in Java.

### Service layer (`service/`)

One `@Service`, `CouponService`, taking `CouponRepository` and `RedemptionRepository` through the constructor rather than field injection — which is what makes it trivial to unit test with plain Mockito mocks and no Spring context.

- **Class-level `@Transactional(readOnly = true)`, method-level `@Transactional` override** — the service is read-only by default; the two methods that actually mutate state (`createCoupon`, `redeemCoupon`) opt back into a writable transaction individually.
- **`redeemCoupon(code, redeemedBy)` is the whole point of this layer.** It looks the coupon up, calls `coupon.redeem()`, and — only if that didn't throw — saves the coupon's new count and a new `Redemption` audit row, in that order, as one transaction. The method makes **no attempt to catch or translate `CouponExhaustedException`** — it propagates unchanged, exactly like a direct call to `Coupon.redeem()` would, so a failed redemption leaves both repositories untouched. This is the same "no partial write" guarantee `Coupon.redeem()` already gives at the domain layer, now proven to hold at the service layer too (`CouponServiceTest.redeemCoupon_exactBoundary_persistsExactlyNTimes` asserts exactly N `save()` calls on each repository, never N+1).
- **Custom unchecked exceptions, one per failure case** (`service/exception/`) — `CouponNotFoundException` and `DuplicateCouponCodeException`. Each has a private constructor and a named static factory (`CouponNotFoundException.forCode(code)`) instead of a public constructor, so every call site reads as a sentence and it's impossible to construct one with the wrong argument in the wrong place.
- **`createCoupon` fails fast** — it checks for a duplicate code *before* constructing the new `Coupon`, so a taken code never reaches a save call. `CouponServiceTest` asserts this directly (`verify(couponRepository, never()).save(any())` on the duplicate-code test), not just the exception type.
- **`getCoupon` and `getRedemptionHistory`** are the read side: both look the coupon up by code (throwing `CouponNotFoundException` if it doesn't exist) before doing anything else, and `getRedemptionHistory` then delegates straight to `RedemptionRepository.findByCouponIdOrderByRedeemedAtAsc` — no extra logic of its own.

## API layer (`api/`)

One `@RestController`, `CouponController`, over the service layer, plus one `@RestControllerAdvice`, `ApiExceptionHandler`, that maps every exception the service (and domain) layers already throw onto the correct HTTP status. **No business logic lives in this layer** — every real decision (is the code taken, does the coupon exist, is it exhausted, is `redeemedBy` a valid email) already happens in `CouponService`/`Coupon`; the controller's job is strictly to unpack a request, call the service, and re-wrap the result.

| Method | Path | Calls | Success | Failure |
|---|---|---|---|---|
| `POST` | `/api/coupons` | `createCoupon` | `201` + `CouponResponse` | `409` (duplicate code), `400` (blank code / negative limit) |
| `GET` | `/api/coupons/{code}` | `getCoupon` | `200` + `CouponResponse` | `404` (unknown code) |
| `GET` | `/api/coupons/{code}/redemptions` | `getRedemptionHistory` | `200` + `List<RedemptionResponse>` | `404` (unknown code) |
| `POST` | `/api/coupons/{code}/redemptions` | `redeemCoupon` | `201` + `RedemptionResponse` | `404` (unknown code), `409` (exhausted), `400` (malformed `redeemedBy`) |

- **DTOs are records, not the entities themselves** (`api/dto/`) — `CouponController` never returns a `Coupon`/`Redemption` directly. `CouponResponse` includes `remainingRedemptions`/`exhausted`, which are derived Java methods (`Coupon.remainingRedemptions()`/`isExhausted()`), not stored columns — a client would have no way to compute them itself without this DTO. `RedemptionResponse` exposes only `couponId` (never a nested coupon object), touching just the `.getId()` of the lazy `@ManyToOne` association — the same safe pattern used elsewhere in this series, since `open-in-view: false` means the transaction is already closed by the time a DTO is built, and touching anything beyond an id on a lazy, unloaded association at that point throws `LazyInitializationException`.
- **No Bean Validation annotations** (`@NotBlank`, `@Valid`, etc.) on the request DTOs, unlike some sibling lessons in this series — consistent with this project's own established style (`Coupon`'s hand-written constructor checks, `CouponService.requireValidRedeemer`'s regex check), the API layer passes request fields straight through and lets `ApiExceptionHandler` translate whatever the service/domain throws.
- **`ApiExceptionHandler` groups exceptions by their HTTP meaning, not their Java type hierarchy**: `CouponNotFoundException` → `404`; `DuplicateCouponCodeException` and `CouponExhaustedException` → `409` (both are "well-formed request, but it conflicts with current data"); `InvalidRedeemerException` and the domain's own `IllegalArgumentException` → `400` (both are "the request body itself was malformed"). Every error response shares one shape, `ErrorResponse` (`status`, `error`, `message`, `timestamp`), regardless of which endpoint or exception produced it.

## API Testing / Swagger

The API is documented automatically via springdoc-openapi/Swagger UI (already wired in via `OpenApiConfig` and the `springdoc-openapi-starter-webmvc-ui` dependency in `pom.xml`) — no endpoint needs any manual `@Schema`/`@ApiResponse` bookkeeping beyond the `@Tag`/`@Operation` annotations already on `CouponController`.

**For a full walkthrough of testing every endpoint by hand through Swagger UI** — starting the app, opening `http://localhost:8080/swagger-ui.html`, using "Try it out," and worked examples for every success and error scenario this API has (create → get → redeem to exhaustion → 409/404/400) — see **[`docs/swagger-testing.md`](docs/swagger-testing.md)**. There are deliberately no automated Swagger/OpenAPI tests in this repository; `CouponControllerTest` (see below) is the automated coverage for this layer instead.

## Testing approach

**Unit tests only, as requested.** Two kinds live side by side, but neither ever touches Spring or a real database:

- **`src/test/java/.../domain/`** — plain JUnit 5 + AssertJ, no mocks needed because the entities have no collaborators.
  - **`CouponTest`** is where the boundary precision lives. The central test, `redeem_succeedsExactlyNTimesThenFails`, is a `@ParameterizedTest` run against several values of N (1, 2, 5, 10): it calls `redeem()` exactly N times, asserting the count and the remaining-redemptions figure after *every single call* (not just at the end), then asserts the `(N+1)`th call throws `CouponExhaustedException` **and** that the count afterward is still exactly N — proving the rejected write had no side effect. A second test (`redeem_repeatedFailuresPastExhaustion_countNeverMoves`) hammers an exhausted coupon five more times to check the count really is stuck, not just correct on the first failure. Construction validation (blank/null code, negative limit, the zero-limit edge case) and code-based equality round out the class, along with four tests for `isNearExhaustion(threshold)` — the pure-Java mirror of the `Coupon.findNearExhaustion` named query, added specifically so that query's predicate has something unit-testable behind it (see [`docs/queries.md`](docs/queries.md#tests-added-for-this-change)).
  - **`RedemptionTest`** checks construction validation (null coupon, blank/null `redeemedBy`), that `redeemedAt` is stamped to "now" without needing a database, that `redeemedBy` is trimmed, and that two separately-constructed redemptions for the same coupon and person are *not* equal — confirming the deliberate fall-back to identity equality.
- **`src/test/java/.../service/`** — `CouponServiceTest`, using `@ExtendWith(MockitoExtension.class)` with `@Mock` repositories and the service constructed by hand in `@BeforeEach`. `Coupon` itself is used as a **real object**, never a mock, wherever `redeem()`'s actual behavior matters — mocking it would only prove the test calls a stub, not that the service wires the real domain logic together correctly. It covers every method: `createCoupon` (saves when free, rejects a duplicate code without saving), `getCoupon`/`getRedemptionHistory` (found vs. `CouponNotFoundException`), and `redeemCoupon` — success (count increments, both repositories saved), an unknown code, an already-exhausted coupon (`CouponExhaustedException`, **zero** saves on either repository), a malformed/blank/null `redeemedBy` (`InvalidRedeemerException`, no repository touched at all), and a `@ParameterizedTest` boundary test (N = 1, 3, 5) asserting `save()` is called on each repository *exactly* N times, never N+1 — the service-layer proof of the same guarantee `CouponTest` already proves at the domain layer.
- **`src/test/java/.../api/`** — `CouponControllerTest`, using `@WebMvcTest(CouponController.class)`: Spring's own controller-slice test facility. This starts a minimal Spring context containing only the web layer (`CouponController` + `ApiExceptionHandler` + Jackson JSON handling) — no `@Service`/`@Repository` beans, no database. `@MockBean` replaces `CouponService` with a Mockito mock, and `MockMvc` sends simulated HTTP requests through the real controller and gets a real status code + JSON body back. It covers every endpoint: a success case (`2xx`, correct response body, correct service arguments via `verify(...)`) and every documented failure case (`404`/`409`/`400`, asserted via `jsonPath` on the `ErrorResponse` body) — proving `CouponController` routes correctly and `ApiExceptionHandler` maps each service exception to the right status, without a real service or database anywhere in the test.

Every test runs in milliseconds, with no full Spring context (`@WebMvcTest` only loads the web layer, never `@SpringBootTest`) and no database. Run them with:

```bash
mvn test
```

There is no need to have Postgres running to build or test this layer — Postgres and Flyway only matter if you start the full Spring context (`mvn spring-boot:run`), at which point you'd need a local database matching `application.yml`:

```bash
createdb coupon_redemption
createuser coupon_redemption --pwprompt   # set the password to "coupon_redemption", or override via env vars
```

Then override the defaults if needed:

```bash
DB_USERNAME=coupon_redemption DB_PASSWORD=coupon_redemption mvn spring-boot:run
```

Once the app is actually running, Swagger UI is reachable at `http://localhost:8080/swagger-ui.html` — see [API Testing / Swagger](#api-testing--swagger) above for a full manual-testing walkthrough.

## What's not tested here

Everything below needs a real (or embedded) database, which makes it an *integration* test, not a unit test — deliberately out of scope for this layer, and left for a later, progressive step:

- Whether any of the six repository query methods (see [`docs/queries.md`](docs/queries.md)) — derived, named, or native — actually return correct results against real rows (`@DataJpaTest` or Testcontainers).
- Whether `chk_coupons_redemption_count_within_limit` really rejects an attempt to persist a `redemption_count` above `max_redemptions` at the database level.
- Whether `Coupon.redeem()`'s check-then-increment — and `CouponService.redeemCoupon`'s look-up-then-`redeem()`-then-save on top of it — is safe under *concurrent* writes to the same row. Every test at both layers is single-threaded, sequential calls to one in-memory object standing in for one database row. Two requests racing to redeem the last remaining use of the same coupon is a real bug class (a classic TOCTOU/lost-update problem) that needs either a database-level guard (e.g. optimistic locking with `@Version`, or `SELECT ... FOR UPDATE`) and a genuinely concurrent test to prove it — both are explicitly future work, not covered by anything in this repository yet.
- End-to-end wiring through a real Spring context (`@SpringBootTest`) — nothing here proves the entities, repositories, Flyway migrations, `CouponService`'s `@Transactional` boundaries, and `CouponController`'s routing all agree with each other at runtime, outside of `ddl-auto: validate` catching gross entity/schema mismatches at startup. `@WebMvcTest` in `CouponControllerTest` proves the web layer's own behavior in isolation (with `CouponService` mocked out) — it doesn't prove the real service or a real database are wired in correctly underneath it.
- Automated Swagger/OpenAPI testing — there's no test in this repository that starts the app and hits `/swagger-ui.html` or `/v3/api-docs`. `docs/swagger-testing.md` is a manual-testing guide for a human, by design (see [API Testing / Swagger](#api-testing--swagger)).

## Project layout

```
src/main/java/com/couponredemption/
  CouponVoucherRedemptionApplication.java   entry point
  config/OpenApiConfig.java                 Swagger metadata (no endpoints yet)
  domain/
    Coupon.java                             the counter + the redeem() boundary logic
    CouponExhaustedException.java           thrown when redeem() is called past the limit
    Redemption.java                         the audit trail of one successful redemption
  repository/
    CouponRepository.java                   derived / named / native query techniques
    RedemptionRepository.java               derived / named / native queries supporting the audit trail
    CouponRedemptionSummary.java            projection for the native summary query
    DailyRedemptionCount.java               projection for the native daily-bucket query
  service/
    CouponService.java                      redeemCoupon orchestration + createCoupon/getCoupon/getRedemptionHistory
    exception/
      CouponNotFoundException.java          thrown when a coupon code has no matching row
      DuplicateCouponCodeException.java     thrown when createCoupon is called with a taken code
      InvalidRedeemerException.java         thrown when redeemedBy isn't shaped like an email address
  api/
    CouponController.java                   REST endpoints over CouponService - no business logic
    ApiExceptionHandler.java                maps every service/domain exception to an HTTP status
    dto/
      CreateCouponRequest.java              POST /api/coupons request body
      CouponResponse.java                   Coupon, as seen over HTTP
      RedeemCouponRequest.java              POST /api/coupons/{code}/redemptions request body
      RedemptionResponse.java               Redemption, as seen over HTTP
      ErrorResponse.java                    the one JSON shape every error response uses
src/main/resources/
  application.yml                           datasource + JPA config used when actually running the app
  db/migration/V1__init_schema.sql          schema, foreign key, and the boundary CHECK constraints
src/test/java/com/couponredemption/
  domain/
    CouponTest.java                         the boundary precision tests
    RedemptionTest.java                     audit-trail construction and equality tests
  service/
    CouponServiceTest.java                  Mockito unit tests for every CouponService method
  api/
    CouponControllerTest.java               @WebMvcTest unit tests for every endpoint
docs/
  erd.md                                    entity-relationship diagram and table-by-table notes
  uml.md                                    class diagram, repository interfaces, and design rationale
  queries.md                                every named/native query explained, with the JPQL/SQL inline
  swagger-testing.md                        manual API-testing walkthrough via Swagger UI
```
