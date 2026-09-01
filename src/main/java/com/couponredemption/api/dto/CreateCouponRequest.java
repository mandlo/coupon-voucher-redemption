package com.couponredemption.api.dto;

// NEW TERM: Java `record`
// WHAT: A compact way to declare an immutable data-carrier class. Writing
//       `record CreateCouponRequest(String code, int maxRedemptions) {}`
//       automatically generates a constructor, getters (named `code()` and
//       `maxRedemptions()`, not `getCode()`), equals()/hashCode(), and
//       toString() - all the boilerplate a plain class would need by hand.
// WHY: This class exists purely to describe the JSON shape of a
//      "create a coupon" request body - it has no behavior of its own, so a
//      record (all data, no logic) is a better fit than a full class.
// WHEN: Used for every request/response DTO in this API layer.
// HOW: Jackson (Spring Boot's default JSON library) reads an incoming JSON
//      body like {"code": "SUMMER10", "maxRedemptions": 5} and maps each
//      field straight onto the record's constructor by name.
//
// Deliberately NO @NotBlank/@Valid here. This project has never used Bean
// Validation annotations anywhere (see Coupon's own constructor, or
// CouponService.requireValidRedeemer) - it hand-rolls every validation rule
// instead. Staying consistent with that: this DTO carries the raw request
// data through untouched, and CouponController passes it straight to
// CouponService, which is where "code must not be blank" /
// "maxRedemptions must not be negative" are already enforced (by Coupon's
// constructor) and already unit-tested (in CouponTest). ApiExceptionHandler
// is what turns that IllegalArgumentException into an HTTP 400.
public record CreateCouponRequest(String code, int maxRedemptions) {
}
