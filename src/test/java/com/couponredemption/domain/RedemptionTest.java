package com.couponredemption.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RedemptionTest {

    @Test
    @DisplayName("a redemption records the coupon, the redeemer, and stamps redeemedAt")
    void construction_setsAllFields() {
        Coupon coupon = new Coupon("SUMMER10", 5);
        Instant before = Instant.now();

        Redemption redemption = new Redemption(coupon, "customer@example.com");

        Instant after = Instant.now();
        assertThat(redemption.getCoupon()).isEqualTo(coupon);
        assertThat(redemption.getRedeemedBy()).isEqualTo("customer@example.com");
        assertThat(redemption.getRedeemedAt()).isBetween(before.minus(1, ChronoUnit.SECONDS), after);
    }

    @Test
    @DisplayName("redeemedBy is trimmed on construction")
    void redeemedBy_isTrimmed() {
        Coupon coupon = new Coupon("SUMMER10", 5);

        Redemption redemption = new Redemption(coupon, "  customer@example.com  ");

        assertThat(redemption.getRedeemedBy()).isEqualTo("customer@example.com");
    }

    @Test
    @DisplayName("a null coupon is rejected")
    void nullCoupon_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Redemption(null, "customer@example.com"))
                .withMessageContaining("coupon");
    }

    @Test
    @DisplayName("a blank redeemedBy is rejected")
    void blankRedeemedBy_isRejected() {
        Coupon coupon = new Coupon("SUMMER10", 5);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Redemption(coupon, "   "))
                .withMessageContaining("redeemedBy");
    }

    @Test
    @DisplayName("a null redeemedBy is rejected")
    void nullRedeemedBy_isRejected() {
        Coupon coupon = new Coupon("SUMMER10", 5);

        assertThatIllegalArgumentException().isThrownBy(() -> new Redemption(coupon, null));
    }

    @Test
    @DisplayName("two separately-built redemptions for the same coupon and redeemer are not equal")
    void equals_fallsBackToIdentity() {
        Coupon coupon = new Coupon("SUMMER10", 5);

        Redemption first = new Redemption(coupon, "customer@example.com");
        Redemption second = new Redemption(coupon, "customer@example.com");

        assertThat(first).isNotEqualTo(second);
    }
}
