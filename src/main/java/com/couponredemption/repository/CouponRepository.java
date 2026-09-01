package com.couponredemption.repository;

import com.couponredemption.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Three query techniques, side by side, on purpose:
 * <ol>
 *   <li>{@link #findByCode(String)} - a <b>derived</b> query, generated from
 *       the method name, no SQL/JPQL written at all.</li>
 *   <li>{@link #findExhausted()} - a <b>named</b> query: Spring Data matches
 *       this method to {@code Coupon.findExhausted}, declared with
 *       {@code @NamedQuery} on the {@link Coupon} entity itself, because the
 *       method name matches the query name exactly.</li>
 *   <li>{@link #findRedemptionSummaries()} - a <b>native</b> query: a plain
 *       SQL join and aggregate mapped onto the {@link CouponRedemptionSummary}
 *       projection, for the one comparison ("does the counter match the row
 *       count?") that a derived or JPQL query can't express as directly.</li>
 * </ol>
 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    List<Coupon> findExhausted();

    @Query(value = """
            SELECT c.code                          AS code,
                   c.max_redemptions                AS maxRedemptions,
                   c.redemption_count                AS redemptionCount,
                   COUNT(r.id)                       AS actualRedemptionRows
            FROM coupons c
            LEFT JOIN redemptions r ON r.coupon_id = c.id
            GROUP BY c.id, c.code, c.max_redemptions, c.redemption_count
            ORDER BY c.code
            """, nativeQuery = true)
    List<CouponRedemptionSummary> findRedemptionSummaries();
}
