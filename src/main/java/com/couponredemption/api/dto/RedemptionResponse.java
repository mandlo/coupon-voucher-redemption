package com.couponredemption.api.dto;

import com.couponredemption.domain.Redemption;

import java.time.Instant;

public record RedemptionResponse(
        Long id,
        Long couponId,
        String redeemedBy,
        Instant redeemedAt
) {

    public static RedemptionResponse from(Redemption redemption) {
        return new RedemptionResponse(
                redemption.getId(),
                // NEW TERM (Java/JPA concept, not a class): lazy association +
                // open-in-view=false
                // WHAT: Redemption.coupon is mapped @ManyToOne(fetch = LAZY) -
                //       Hibernate doesn't load the full Coupon row until
                //       something actually calls a getter on it. This
                //       application also sets `spring.jpa.open-in-view: false`
                //       in application.yml, which means the database
                //       transaction is already closed by the time this method
                //       runs (transactions are scoped to CouponService's
                //       @Transactional methods, not to the whole HTTP request).
                // WHY IT MATTERS: calling redemption.getCoupon().getCode() at
                //      this point would throw LazyInitializationException -
                //      there's no open transaction left to fetch it with.
                //      Calling .getId() is always safe, though: a Hibernate
                //      proxy carries its own id without needing to load
                //      anything from the database.
                // HOW: this is exactly why RedemptionResponse only exposes
                //      `couponId`, never a nested coupon code or object.
                redemption.getCoupon().getId(),
                redemption.getRedeemedBy(),
                redemption.getRedeemedAt()
        );
    }
}
