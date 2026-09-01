package com.couponredemption.repository;

import com.couponredemption.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Three query techniques, side by side, on purpose, so they're easy to
 * compare. See {@code docs/queries.md} for the full write-up of why each one
 * is built the way it is.
 * <ol>
 *   <li><b>Derived</b> - {@link #findByCode(String)}. Spring Data parses the
 *       method name ({@code findBy} + {@code Code}) and writes the JPQL
 *       itself; no query string exists anywhere in this codebase.</li>
 *   <li><b>Named</b> - {@link #findExhausted()} and
 *       {@link #findNearExhaustion(int)}. The JPQL is declared once, with
 *       {@code @NamedQuery}, directly on the {@link Coupon} entity. Neither
 *       repository method carries a {@code @Query} annotation - Spring Data
 *       resolves a named query automatically when the method name matches
 *       {@code <EntityName>.<methodName>} exactly, which is why the method
 *       names here must match the {@code name = "Coupon.___"} strings on the
 *       entity precisely.</li>
 *   <li><b>Native</b> - {@link #findRedemptionSummaries()}. Raw SQL against
 *       the actual {@code coupons}/{@code redemptions} tables, mapped onto
 *       the {@link CouponRedemptionSummary} projection, for the one
 *       comparison ("does the counter match the row count?") that needs an
 *       aggregate across a join - something with no equivalent field on the
 *       {@code Coupon} entity for JPQL to query against.</li>
 * </ol>
 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    // Resolves to the "Coupon.findExhausted" @NamedQuery on Coupon.java -
    // coupons with zero redemptions remaining.
    List<Coupon> findExhausted();

    // Resolves to the "Coupon.findNearExhaustion" @NamedQuery on Coupon.java.
    // @Param is required here (rather than relying on -parameters compiler
    // metadata) because the named query binds its parameter by the name
    // ":threshold" written into the JPQL string itself - Spring Data has no
    // other way to connect that placeholder back to this method's argument.
    List<Coupon> findNearExhaustion(@Param("threshold") int threshold);

    // Native by necessity, not by choice: comparing Coupon.redemptionCount
    // (a denormalized counter, maintained only by Coupon.redeem()) against a
    // COUNT(*) of actual Redemption rows means aggregating across a JOIN -
    // there is no "redemptionCount" column on the redemptions side and no
    // JPQL path from Coupon to a COUNT of its Redemptions, since Coupon
    // holds no @OneToMany back to Redemption (see docs/uml.md for why).
    @Query(value = """
            -- One row per coupon: its own counter, next to how many
            -- redemption rows actually exist for it. LEFT JOIN (not JOIN)
            -- so a coupon with zero redemptions still gets a row, with
            -- COUNT(r.id) correctly landing on 0 rather than being dropped.
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
}
