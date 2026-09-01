package com.couponredemption.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    // --- construction / validation ---------------------------------------

    @Test
    @DisplayName("a freshly-built coupon starts with a zero redemption count")
    void newCoupon_startsAtZero() {
        Coupon coupon = new Coupon("SUMMER10", 5);

        assertThat(coupon.getCode()).isEqualTo("SUMMER10");
        assertThat(coupon.getMaxRedemptions()).isEqualTo(5);
        assertThat(coupon.getRedemptionCount()).isZero();
        assertThat(coupon.remainingRedemptions()).isEqualTo(5);
        assertThat(coupon.isExhausted()).isFalse();
    }

    @Test
    @DisplayName("code is trimmed on construction")
    void code_isTrimmed() {
        Coupon coupon = new Coupon("  SUMMER10  ", 5);

        assertThat(coupon.getCode()).isEqualTo("SUMMER10");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank code is rejected")
    void blankCode_isRejected(String blankCode) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coupon(blankCode, 5))
                .withMessageContaining("code");
    }

    @Test
    @DisplayName("a null code is rejected")
    void nullCode_isRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Coupon(null, 5));
    }

    @Test
    @DisplayName("a negative redemption limit is rejected")
    void negativeMaxRedemptions_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coupon("SUMMER10", -1))
                .withMessageContaining("negative");
    }

    @Test
    @DisplayName("a limit of zero is allowed, but leaves the coupon exhausted immediately")
    void zeroMaxRedemptions_startsExhausted() {
        Coupon coupon = new Coupon("SUMMER10", 0);

        assertThat(coupon.isExhausted()).isTrue();
        assertThat(coupon.remainingRedemptions()).isZero();
    }

    // --- the boundary: the Nth redeem() succeeds, the (N+1)th fails -------

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    @DisplayName("exactly N redemptions succeed, and the (N+1)th fails without changing the count")
    void redeem_succeedsExactlyNTimesThenFails(int maxRedemptions) {
        Coupon coupon = new Coupon("SUMMER10", maxRedemptions);

        for (int i = 1; i <= maxRedemptions; i++) {
            coupon.redeem();
            assertThat(coupon.getRedemptionCount())
                    .as("redemption count after redemption #%d of %d", i, maxRedemptions)
                    .isEqualTo(i);
            assertThat(coupon.remainingRedemptions()).isEqualTo(maxRedemptions - i);
        }

        assertThat(coupon.isExhausted()).isTrue();

        assertThatThrownBy(coupon::redeem)
                .isInstanceOf(CouponExhaustedException.class)
                .hasMessageContaining("SUMMER10")
                .hasMessageContaining(String.valueOf(maxRedemptions));

        // The failed (N+1)th call must be a no-op: the count stays at N,
        // not N+1 and not N-1 - this is the "assertion precision" the
        // boundary is named for.
        assertThat(coupon.getRedemptionCount()).isEqualTo(maxRedemptions);
        assertThat(coupon.remainingRedemptions()).isZero();
    }

    @Test
    @DisplayName("repeated failed redemptions past exhaustion never move the count")
    void redeem_repeatedFailuresPastExhaustion_countNeverMoves() {
        Coupon coupon = new Coupon("SUMMER10", 3);
        coupon.redeem();
        coupon.redeem();
        coupon.redeem();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(coupon::redeem).isInstanceOf(CouponExhaustedException.class);
        }

        assertThat(coupon.getRedemptionCount()).isEqualTo(3);
    }

    // --- equality: business key is the code -------------------------------

    @Test
    @DisplayName("two coupons with the same code are equal, regardless of redemption count")
    void equals_sameCode_isEqualRegardlessOfCount() {
        Coupon fresh = new Coupon("SUMMER10", 5);
        Coupon partiallyRedeemed = new Coupon("SUMMER10", 5);
        partiallyRedeemed.redeem();

        assertThat(fresh).isEqualTo(partiallyRedeemed);
        assertThat(fresh).hasSameHashCodeAs(partiallyRedeemed);
    }

    @Test
    @DisplayName("coupons with different codes are not equal")
    void equals_differentCode_isNotEqual() {
        Coupon summer = new Coupon("SUMMER10", 5);
        Coupon winter = new Coupon("WINTER10", 5);

        assertThat(summer).isNotEqualTo(winter);
    }
}
