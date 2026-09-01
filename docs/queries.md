# Named Queries and Native Queries

`CouponRepository` and `RedemptionRepository` each use three Spring Data mechanisms: the free CRUD methods every `JpaRepository` gets, a **derived** query, a **named** query, and a **native** query. This doc walks through every non-trivial query method, why it's written the way it is, and why it needed the mechanism it uses.

## How Spring Data picks a query for a method

For each repository method, Spring Data JPA looks for a query in this order:

1. An explicit `@Query` annotation on the method (covers every native query here).
2. A **named query** whose name matches `<EntityName>.<methodName>` exactly.
3. Otherwise, derive a query by parsing the method name (`findByCode`, `countByCouponId`, …).

That order is why `findExhausted`, `findNearExhaustion`, and `findRecentForCoupon` don't need a `@Query` annotation at all — Spring Data finds the matching `@NamedQuery` on the entity before it would try (and in this case, fail — none of the three is a real property path) to derive one from the method name.

## Named queries

A named query is JPQL — it queries against **entity fields**, not table columns — declared once on the entity with `@NamedQuery(name, query)`, then referenced from a repository method whose name matches the part after the dot. `@NamedQuery` is `@Repeatable` (Jakarta Persistence 2.2+), so `Coupon` declares two of them directly, with no wrapping `@NamedQueries` annotation needed.

| Entity | Name | JPQL |
|---|---|---|
| `Coupon` | `Coupon.findExhausted` | `SELECT c FROM Coupon c WHERE c.redemptionCount >= c.maxRedemptions` |
| `Coupon` | `Coupon.findNearExhaustion` | `SELECT c FROM Coupon c WHERE (c.maxRedemptions - c.redemptionCount) <= :threshold AND c.maxRedemptions > 0 ORDER BY (c.maxRedemptions - c.redemptionCount) ASC` |
| `Redemption` | `Redemption.findRecentForCoupon` | `SELECT r FROM Redemption r WHERE r.coupon.id = :couponId ORDER BY r.redeemedAt DESC` |

```java
// Coupon.java
@NamedQuery(
        name = "Coupon.findExhausted",
        query = "SELECT c FROM Coupon c WHERE c.redemptionCount >= c.maxRedemptions"
)
@NamedQuery(
        name = "Coupon.findNearExhaustion",
        query = "SELECT c FROM Coupon c WHERE (c.maxRedemptions - c.redemptionCount) <= :threshold "
                + "AND c.maxRedemptions > 0 ORDER BY (c.maxRedemptions - c.redemptionCount) ASC"
)
public class Coupon { ... }

// CouponRepository.java
List<Coupon> findExhausted();
List<Coupon> findNearExhaustion(@Param("threshold") int threshold);
```

- **`findExhausted`** is simple enough that it reads almost like the `isExhausted()` method already on `Coupon` — that's intentional. It's the query version of the same rule the entity enforces in Java: `redemptionCount >= maxRedemptions`.
- **`findNearExhaustion`** is the more interesting one: it's not a property on `Coupon` at all (there's no `nearExhaustion` field), so Spring Data could never derive it from a method name — a named query (or `@Query`) is the only option. It's also the query an ops dashboard or alert would actually run: "which coupons have `threshold` or fewer redemptions left?" `AND c.maxRedemptions > 0` deliberately excludes coupons created with a limit of zero — those are exhausted from the moment they exist, not "running low," so surfacing them here would be noise.
- **`Redemption.findRecentForCoupon`** walks `r.coupon.id` — a real association traversal in JPQL, exactly like calling `redemption.getCoupon().getId()` in Java, because `Redemption.coupon` is a mapped `@ManyToOne` field. `findByCouponIdOrderByRedeemedAtAsc` (see below) could have answered almost the same question via a derived query; this one exists specifically to show the named-query mechanism against an association, sorted the opposite direction (newest first, for "what just happened to this coupon" rather than "the full history in order").

**Why JPQL:** it's portable across databases (Hibernate translates it to whatever SQL the configured dialect needs) and it's checked against the entity model at startup, so a typo in a field name fails fast — try renaming `redemptionCount` without updating these strings and the application won't start.

## Native queries

A native query is raw SQL against the **actual table and column names**, opted into per-method with `@Query(value = "...", nativeQuery = true)`.

| Repository | Method | Native SQL |
|---|---|---|
| `CouponRepository` | `findRedemptionSummaries` | `LEFT JOIN`s `coupons` to `redemptions`, `GROUP BY` coupon, comparing the stored counter to an actual `COUNT` |
| `RedemptionRepository` | `findDailyRedemptionCounts` | buckets one coupon's redemptions by calendar day via `date_trunc`, `GROUP BY`, `COUNT(*)` |

```java
// CouponRepository.java
@Query(value = """
        SELECT c.code                     AS code,
               c.max_redemptions          AS maxRedemptions,
               c.redemption_count         AS redemptionCount,
               COUNT(r.id)                AS actualRedemptionRows
        FROM coupons c
        LEFT JOIN redemptions r ON r.coupon_id = c.id
        GROUP BY c.id, c.code, c.max_redemptions, c.redemption_count
        ORDER BY c.code
        """, nativeQuery = true)
List<CouponRedemptionSummary> findRedemptionSummaries();
```

`findRedemptionSummaries` exists **because** JPQL can't express it cleanly: comparing `Coupon.redemptionCount` (a field that lives entirely on `Coupon`) against a `COUNT` of `Redemption` rows means aggregating across a join that has no corresponding path on the entity model — `Coupon` holds no `@OneToMany List<Redemption>` for JPQL to `COUNT()` over (see [uml.md](uml.md#where-the-counting-logic-lives--and-where-it-deliberately-doesnt) for why that reference doesn't exist). `LEFT JOIN` (not a plain `JOIN`) matters here specifically: a brand-new coupon with zero redemptions still needs a row in the result, with `COUNT(r.id)` correctly landing on `0` — an inner `JOIN` would silently drop it instead.

```java
// RedemptionRepository.java
@Query(value = """
        SELECT date_trunc('day', r.redeemed_at)::date AS day,
               COUNT(*)                                AS redemptionCount
        FROM redemptions r
        WHERE r.coupon_id = :couponId
        GROUP BY day
        ORDER BY day
        """, nativeQuery = true)
List<DailyRedemptionCount> findDailyRedemptionCounts(@Param("couponId") Long couponId);
```

`findDailyRedemptionCounts` is native for a different reason: `date_trunc` is a PostgreSQL-specific function with no portable JPQL equivalent, and "how many redemptions per calendar day" is an aggregate with no field on `Redemption` to query against at all — there's nothing here a derived or named query could ever produce, native SQL is the only way to ask this question.

**Why native SQL:** full access to database-specific functions (`date_trunc`) and to aggregates/joins that don't map back to entity fields, at the cost of coupling the query to PostgreSQL's schema and dialect — rename a column in Java and the JPQL queries fail to compile-check against the entity at startup, but a native query just fails silently at runtime the next time it actually runs.

## What these queries are *for*

Both native queries exist for the same reason `redemption_count` has a `CHECK` constraint mirroring it in SQL (see [erd.md](erd.md#two-enforcements-of-one-invariant)): `Coupon.redemptionCount` is a denormalized counter, maintained only by `Coupon.redeem()` incrementing it in memory. `findRedemptionSummaries` is the query that would catch that counter ever drifting out of sync with the independent `Redemption` ledger — the same "counted resource" concern the whole domain is about, expressed as SQL instead of a Java assertion. `findDailyRedemptionCounts` and `findNearExhaustion` are the operational counterpart: not "is the data consistent," but "which coupons need attention, and when did the load actually happen."

## Testing note

Every test in this project is a pure unit test: `CouponTest` and `RedemptionTest` call plain constructors and methods with AssertJ assertions, no Spring context and no database anywhere. Neither can verify that a JPQL string or a native SQL string is actually *correct* — there's no repository bean running in a unit test, so none of the six query methods described above is exercised by anything in `src/test` yet.

Proving these queries work means running them against a real (or embedded) database — typically `@DataJpaTest` with either an in-memory database or a Testcontainers-managed PostgreSQL instance, asserting on rows the test itself inserts. That, along with proving the `chk_coupons_redemption_count_within_limit` constraint actually rejects a bad write, and proving `redeem()` is safe under concurrent calls, is the deliberate next step up in this project's progressive-testing path — see [What's not tested here](../README.md#whats-not-tested-here).
