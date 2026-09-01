package com.couponredemption.service.exception;

/**
 * Thrown when {@code createCoupon} is called with a code that already
 * belongs to another coupon. {@code Coupon.code} is unique both at the
 * database level (see V1__init_schema.sql) and as the entity's own business
 * key (see {@code Coupon.equals}); this exception is the service layer's way
 * of catching that before a save is even attempted.
 */
public class DuplicateCouponCodeException extends RuntimeException {

    private DuplicateCouponCodeException(String message) {
        super(message);
    }

    public static DuplicateCouponCodeException forCode(String code) {
        return new DuplicateCouponCodeException("A coupon with code " + code + " already exists");
    }
}
