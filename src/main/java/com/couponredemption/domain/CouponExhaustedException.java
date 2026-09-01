package com.couponredemption.domain;

/**
 * Thrown by {@link Coupon#redeem()} when a coupon has already been redeemed
 * {@code maxRedemptions} times. A private constructor plus a named static
 * factory ({@link #forCoupon(String, int)}) means every call site reads as a
 * sentence and it's impossible to construct one with the arguments swapped.
 */
public class CouponExhaustedException extends RuntimeException {

    private CouponExhaustedException(String message) {
        super(message);
    }

    public static CouponExhaustedException forCoupon(String code, int maxRedemptions) {
        return new CouponExhaustedException(
                "Coupon " + code + " has already reached its redemption limit of " + maxRedemptions);
    }
}
