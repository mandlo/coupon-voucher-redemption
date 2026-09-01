package com.couponredemption.api.dto;

import com.couponredemption.domain.Coupon;

// This DTO is the HTTP-facing shape of a Coupon. The controller never
// returns a Coupon entity directly - only this record. Two reasons:
//   1. It keeps the JSON contract stable even if Coupon's internal fields
//      ever change shape.
//   2. It lets the response include DERIVED information (remainingRedemptions,
//      exhausted) that isn't a stored column at all - Coupon.remainingRedemptions()
//      and Coupon.isExhausted() are plain Java methods, not @Column fields,
//      so a client would have no way to compute them itself without this.
public record CouponResponse(
        Long id,
        String code,
        int maxRedemptions,
        int redemptionCount,
        int remainingRedemptions,
        boolean exhausted
) {

    // NEW TERM: static factory method (on a DTO, same pattern as the
    // service-layer exceptions' `forCode(...)`/`forValue(...)` methods)
    // WHAT: A `public static` method that builds and returns an instance of
    //       its own class, used here to convert one object (Coupon) into
    //       another (CouponResponse).
    // WHY: Keeps the "how do I build a CouponResponse from a Coupon" logic
    //      in one place, right next to the record it builds, instead of
    //      scattered across every controller method that needs one.
    // WHEN: Called from CouponController wherever a Coupon needs to go out
    //       over HTTP.
    // HOW: Reads every field/derived value off the Coupon and passes them,
    //      in order, to the record's generated constructor.
    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getCode(),
                coupon.getMaxRedemptions(),
                coupon.getRedemptionCount(),
                coupon.remainingRedemptions(),
                coupon.isExhausted()
        );
    }
}
