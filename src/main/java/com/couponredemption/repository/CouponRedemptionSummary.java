package com.couponredemption.repository;

/**
 * Projection for {@link CouponRepository#findRedemptionSummaries()}: the
 * in-memory counter each {@link com.couponredemption.domain.Coupon} carries,
 * next to the actual number of {@link com.couponredemption.domain.Redemption}
 * rows persisted for it. In a correctly-working system the two always match;
 * this projection exists to make that comparison a single query instead of
 * loading every coupon and every redemption into Java to check by hand.
 */
public interface CouponRedemptionSummary {

    String getCode();

    int getMaxRedemptions();

    int getRedemptionCount();

    long getActualRedemptionRows();
}
