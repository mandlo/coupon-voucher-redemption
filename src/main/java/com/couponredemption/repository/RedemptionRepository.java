package com.couponredemption.repository;

import com.couponredemption.domain.Redemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Supports the audit trail rather than being the star of the show - see
 * {@code docs/queries.md} for the full picture across both repositories.
 */
public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

    // Derived query: Spring Data reads "findByCouponIdOrderByRedeemedAtAsc"
    // as "filter where coupon.id = ?, sort by redeemedAt ascending" and
    // writes the JPQL itself.
    List<Redemption> findByCouponIdOrderByRedeemedAtAsc(Long couponId);

    // The literal "running count" this whole domain is about, as a query:
    // the number of Redemption rows that actually exist for a coupon, which
    // should always equal Coupon.redemptionCount. Also derived - Spring Data
    // recognizes the "countBy" prefix and returns a count instead of a list.
    long countByCouponId(Long couponId);

    // Resolves to the "Redemption.findRecentForCoupon" @NamedQuery declared
    // on Redemption.java, because this method's name matches it exactly.
    List<Redemption> findRecentForCoupon(@Param("couponId") Long couponId);

    // Native by necessity: date_trunc('day', ...) is a PostgreSQL function
    // with no equivalent in portable JPQL, and bucketing rows by calendar
    // day plus a GROUP BY/COUNT is an aggregate that has no field on the
    // Redemption entity to query against - a native query is the only way
    // to ask "how many redemptions per day did this coupon get".
    @Query(value = """
            -- ::date truncates the timestamp down to a calendar day (in the
            -- database's session time zone), so every redemption from the
            -- same day collapses into one GROUP BY bucket.
            SELECT date_trunc('day', r.redeemed_at)::date AS day,
                   COUNT(*)                                AS redemptionCount
            FROM redemptions r
            WHERE r.coupon_id = :couponId
            GROUP BY day
            ORDER BY day
            """, nativeQuery = true)
    List<DailyRedemptionCount> findDailyRedemptionCounts(@Param("couponId") Long couponId);
}
