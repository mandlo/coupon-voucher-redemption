# Coupon/Voucher Redemption — Persistence Layer

A coupon/voucher redemption system built with **Java 21**, **Spring Boot 3**, **Maven**, **PostgreSQL**, and **springdoc-openapi (Swagger)**.

This repository contains **only the persistence layer** (JPA entities, Spring Data repositories, Flyway schema migrations), covered by **unit tests only**. There is no service layer and no REST API yet — those are later, progressive steps.

## Why this domain

The whole point of this app is one specific piece of state: a coupon can be redeemed at most `maxRedemptions` times, and `redemptionCount` is a running total that climbs toward that limit. The interesting behavior isn't "can this coupon be redeemed" in general — it's the exact boundary:

- The **Nth** redemption (the last one allowed) must succeed.
- The **(N+1)th** redemption must fail, and must fail *without* changing the count — a rejected write is not a partial write.

That's more state to keep straight across a test than a plain create/read/update/delete scenario (you have to track a counter across repeated calls and assert on it after each one), but it's still sequential logic — no concurrency, no new persistence pattern. It reuses the same shape as a stock counter with an audit trail: `Coupon.redemptionCount` is the counter (compare to a `Product.stockQuantity`), and `Redemption` is the audit trail entity created each time the counter successfully moves (compare to a `StockMovement` row). The new part is entirely about counting precisely and asserting the boundary, not about a new kind of relationship or query.

## Domain model

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

- It records the fact that a redemption happened — which coupon, who redeemed it, when — and nothing about whether it *should* have happened. That decision belongs entirely to `Coupon.redeem()`. A later service layer is what will call `coupon.redeem()` and then save a `Redemption` as one transaction; this layer only models the two things being stored, not how they're wired together.
- `redeemedAt` is stamped with `Instant.now()` **inside the constructor**, not via Hibernate's `@CreationTimestamp`. That's a direct consequence of "unit tests only": `@CreationTimestamp` only populates a field when Hibernate actually persists the entity, which means testing it would require a database. Stamping the timestamp in plain Java means `new Redemption(coupon, "someone@example.com")` has a real, assertable `redeemedAt` with no database and no mocking involved.
- Like `Loan` in an earlier lesson in this series, `Redemption` does **not** override `equals()`/`hashCode()`. There's no natural single-field business key for "one redemption event" — two redemptions of the same coupon by the same person a second apart are two different, real events, not duplicates — so it falls back to identity equality, which is exactly what the tests assert (`equals_fallsBackToIdentity`).

### Repositories (`repository/`)

`CouponRepository` demonstrates three query techniques side by side, on purpose, so they're easy to compare:

1. **Derived query** — `findByCode(String code)`. Spring Data generates the query from the method name; no SQL or JPQL is written anywhere.
2. **Named query** — `findExhausted()`. The query itself (`SELECT c FROM Coupon c WHERE c.redemptionCount >= c.maxRedemptions`) is declared once, with `@NamedQuery`, directly on the `Coupon` entity. The repository method has no `@Query` annotation at all — Spring Data finds `Coupon.findExhausted` automatically because the method name matches the query name exactly.
3. **Native query + projection** — `findRedemptionSummaries()`. A raw SQL `LEFT JOIN` between `coupons` and `redemptions`, grouped by coupon, mapped onto the `CouponRedemptionSummary` projection interface. It returns, per coupon, both `redemptionCount` (the in-memory counter) and `actualRedemptionRows` (a real `COUNT` of persisted `Redemption` rows) side by side — the query that would catch it if the two ever drifted apart. This is the one query here that a derived method or JPQL couldn't express as directly, so it's native by necessity, not by choice.

`RedemptionRepository` is smaller, and supports auditing rather than being the star of the show:

- `findByCouponIdOrderByRedeemedAtAsc(Long couponId)` — every redemption for a coupon, oldest first.
- `countByCouponId(Long couponId)` — the literal "running count" the whole domain is about, expressed as a query: the number of `Redemption` rows that actually exist for a coupon, which should always equal `Coupon.redemptionCount`.

### Schema (`src/main/resources/db/migration/`)

Flyway owns the schema — `spring.jpa.hibernate.ddl-auto` is set to `validate` in `application.yml`, so Hibernate checks the entity mappings against the migrated schema at startup and refuses to start if they disagree, but never generates DDL itself.

`V1__init_schema.sql` creates both tables, a foreign key from `redemptions.coupon_id` to `coupons.id`, an index on that foreign key, and three `CHECK` constraints: `max_redemptions >= 0`, `redemption_count >= 0`, and — the important one — `redemption_count <= max_redemptions`, the database's own copy of the invariant `Coupon.redeem()` enforces in Java.

## Testing approach

**Unit tests only, as requested.** Both test classes live in `src/test/java/.../domain/`, use plain JUnit 5 + AssertJ, and never touch Spring or a database:

- **`CouponTest`** is where the boundary precision lives. The central test, `redeem_succeedsExactlyNTimesThenFails`, is a `@ParameterizedTest` run against several values of N (1, 2, 5, 10): it calls `redeem()` exactly N times, asserting the count and the remaining-redemptions figure after *every single call* (not just at the end), then asserts the `(N+1)`th call throws `CouponExhaustedException` **and** that the count afterward is still exactly N — proving the rejected write had no side effect. A second test (`redeem_repeatedFailuresPastExhaustion_countNeverMoves`) hammers an exhausted coupon five more times to check the count really is stuck, not just correct on the first failure. Construction validation (blank/null code, negative limit, the zero-limit edge case) and code-based equality round out the class.
- **`RedemptionTest`** checks construction validation (null coupon, blank/null `redeemedBy`), that `redeemedAt` is stamped to "now" without needing a database, that `redeemedBy` is trimmed, and that two separately-constructed redemptions for the same coupon and person are *not* equal — confirming the deliberate fall-back to identity equality.

Both classes run in milliseconds, with no Spring context and no database. Run them with:

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

Swagger UI would be reachable at `http://localhost:8080/swagger-ui.html` once a controller layer exists — the `springdoc-openapi` dependency and `OpenApiConfig` are wired in now so that a later API layer has nothing left to configure, but there are no endpoints to document yet.

## What's not tested here

Everything below needs a real (or embedded) database, which makes it an *integration* test, not a unit test — deliberately out of scope for this layer, and left for a later, progressive step:

- Whether `CouponRepository.findByCode`, `findExhausted`, and `findRedemptionSummaries` actually return correct results against real rows (`@DataJpaTest` or Testcontainers).
- Whether `chk_coupons_redemption_count_within_limit` really rejects an attempt to persist a `redemption_count` above `max_redemptions` at the database level.
- Whether `Coupon.redeem()`'s check-then-increment is safe under *concurrent* writes to the same row — this layer's tests are all single-threaded, sequential calls to one in-memory object. Two requests racing to redeem the last remaining use of the same coupon is a real bug class (a classic TOCTOU/lost-update problem) that needs either a database-level guard (e.g. optimistic locking with `@Version`, or `SELECT ... FOR UPDATE`) and a genuinely concurrent test to prove it — both are explicitly future work, not covered by anything in this repository yet.
- End-to-end wiring through a real Spring context (`@SpringBootTest`) — nothing here proves the entities, repositories, and Flyway migrations actually agree with each other outside of `ddl-auto: validate` catching gross mismatches at startup.

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
    RedemptionRepository.java               derived queries supporting the audit trail
    CouponRedemptionSummary.java            projection for the native summary query
src/main/resources/
  application.yml                           datasource + JPA config used when actually running the app
  db/migration/V1__init_schema.sql          schema, foreign key, and the boundary CHECK constraints
src/test/java/com/couponredemption/domain/
  CouponTest.java                           the boundary precision tests
  RedemptionTest.java                       audit-trail construction and equality tests
```
