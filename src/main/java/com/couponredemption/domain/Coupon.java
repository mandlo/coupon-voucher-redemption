package com.couponredemption.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * A coupon that may be redeemed at most {@code maxRedemptions} times.
 * <p>
 * {@code redemptionCount} is the running count this whole domain exists to
 * exercise: {@link #redeem()} is the single write path that increments it,
 * and it is the one method responsible for the exact boundary the app is
 * named after - the Nth call must succeed and the (N+1)th must fail, with no
 * side effect (no count change) on the call that fails. Everything else on
 * this entity (validation, equality) exists only to make that method safe
 * to call and easy to test.
 */
@Entity
@Table(name = "coupons")
@NamedQuery(
        name = "Coupon.findExhausted",
        query = "SELECT c FROM Coupon c WHERE c.redemptionCount >= c.maxRedemptions"
)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "max_redemptions", nullable = false)
    private int maxRedemptions;

    @Column(name = "redemption_count", nullable = false)
    private int redemptionCount;

    protected Coupon() {
    }

    public Coupon(String code, int maxRedemptions) {
        this.code = requireCode(code);
        this.maxRedemptions = requireNonNegative(maxRedemptions);
        this.redemptionCount = 0;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getMaxRedemptions() {
        return maxRedemptions;
    }

    public int getRedemptionCount() {
        return redemptionCount;
    }

    public int remainingRedemptions() {
        return maxRedemptions - redemptionCount;
    }

    public boolean isExhausted() {
        return redemptionCount >= maxRedemptions;
    }

    /**
     * Records one redemption, provided the coupon isn't exhausted yet.
     * <p>
     * The check and the increment happen as one step so there is never a
     * moment where {@code redemptionCount} sits above {@code maxRedemptions}
     * inside this object - the same invariant the database's own
     * {@code chk_coupons_redemption_count_within_limit} constraint enforces
     * independently at the row level (see V1__init_schema.sql).
     *
     * @throws CouponExhaustedException if all {@code maxRedemptions} redemptions
     *                                   have already been used - the call has no
     *                                   effect on {@code redemptionCount} in that case
     */
    public void redeem() {
        if (isExhausted()) {
            throw CouponExhaustedException.forCoupon(code, maxRedemptions);
        }
        redemptionCount++;
    }

    private static String requireCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Coupon code must not be blank");
        }
        return value.trim();
    }

    private static int requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Max redemptions must not be negative");
        }
        return value;
    }

    // Business key equality: two coupons with the same code are the same
    // coupon, regardless of how many times either has been redeemed - this
    // is what lets tests compare a freshly-built Coupon against one that has
    // already had redeem() called on it.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Coupon other)) {
            return false;
        }
        return Objects.equals(code, other.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}
