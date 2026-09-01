package com.couponredemption.service.exception;

/**
 * Thrown when a coupon code has no matching row. Private constructor plus a
 * named static factory ({@link #forCode(String)}) means every call site
 * reads as a sentence and it's impossible to construct one with the wrong
 * argument in the wrong place.
 */
public class CouponNotFoundException extends RuntimeException {

    private CouponNotFoundException(String message) {
        super(message);
    }

    public static CouponNotFoundException forCode(String code) {
        return new CouponNotFoundException("No coupon found with code " + code);
    }
}
