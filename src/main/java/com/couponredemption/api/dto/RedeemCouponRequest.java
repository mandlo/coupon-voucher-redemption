package com.couponredemption.api.dto;

// The JSON body for "redeem this coupon": just who's redeeming it. The
// coupon's code isn't part of this record - it comes from the URL path
// instead (see CouponController.redeemCoupon's @PathVariable), because the
// code identifies WHICH coupon, which is a property of the endpoint being
// called, not of the redemption request itself.
public record RedeemCouponRequest(String redeemedBy) {
}
