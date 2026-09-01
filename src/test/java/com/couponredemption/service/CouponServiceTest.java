package com.couponredemption.service;

import com.couponredemption.domain.Coupon;
import com.couponredemption.domain.CouponExhaustedException;
import com.couponredemption.domain.Redemption;
import com.couponredemption.repository.CouponRepository;
import com.couponredemption.repository.RedemptionRepository;
import com.couponredemption.service.exception.CouponNotFoundException;
import com.couponredemption.service.exception.DuplicateCouponCodeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A true unit test of {@link CouponService}'s own orchestration: no Spring
 * context, no database, {@link CouponRepository}/{@link RedemptionRepository}
 * are Mockito mocks, and the service is constructed by hand in
 * {@link #setUp()}. {@link Coupon} itself is used as a real object, not a
 * mock, everywhere its actual redemption behavior matters - mocking
 * {@code redeem()} would only prove this test calls a stub, not that the
 * service wires the real boundary logic together correctly.
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    private static final String CODE = "SUMMER10";
    private static final String REDEEMED_BY = "customer@example.com";

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private RedemptionRepository redemptionRepository;

    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = new CouponService(couponRepository, redemptionRepository);
    }

    // --- createCoupon -------------------------------------------------------

    @Test
    @DisplayName("createCoupon saves a new coupon when the code is free")
    void createCoupon_savesNewCoupon_whenCodeIsFree() {
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.empty());
        when(couponRepository.save(any(Coupon.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Coupon created = couponService.createCoupon(CODE, 5);

        assertThat(created.getCode()).isEqualTo(CODE);
        assertThat(created.getMaxRedemptions()).isEqualTo(5);
        assertThat(created.getRedemptionCount()).isZero();
    }

    @Test
    @DisplayName("createCoupon rejects a code that's already taken, without saving anything")
    void createCoupon_throwsDuplicateCouponCodeException_whenCodeAlreadyExists() {
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.of(new Coupon(CODE, 5)));

        assertThatThrownBy(() -> couponService.createCoupon(CODE, 10))
                .isInstanceOf(DuplicateCouponCodeException.class)
                .hasMessageContaining(CODE);

        verify(couponRepository, never()).save(any());
    }

    // --- getCoupon ------------------------------------------------------

    @Test
    @DisplayName("getCoupon returns the coupon when it exists")
    void getCoupon_returnsCoupon_whenFound() {
        Coupon coupon = new Coupon(CODE, 5);
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.of(coupon));

        assertThat(couponService.getCoupon(CODE)).isSameAs(coupon);
    }

    @Test
    @DisplayName("getCoupon throws CouponNotFoundException for an unknown code")
    void getCoupon_throwsCouponNotFoundException_whenMissing() {
        when(couponRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponService.getCoupon("UNKNOWN"))
                .isInstanceOf(CouponNotFoundException.class)
                .hasMessageContaining("UNKNOWN");
    }

    // --- redeemCoupon: the boundary, wired through a service ---------------

    @Test
    @DisplayName("redeemCoupon increments the coupon and saves a redemption on success")
    void redeemCoupon_incrementsCouponAndSavesRedemption_onSuccess() {
        Coupon coupon = new Coupon(CODE, 5);
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(Redemption.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Redemption redemption = couponService.redeemCoupon(CODE, REDEEMED_BY);

        assertThat(coupon.getRedemptionCount()).isEqualTo(1);
        assertThat(redemption.getCoupon()).isSameAs(coupon);
        assertThat(redemption.getRedeemedBy()).isEqualTo(REDEEMED_BY);
        verify(couponRepository).save(coupon);
    }

    @Test
    @DisplayName("redeemCoupon throws CouponNotFoundException for an unknown code, saving nothing")
    void redeemCoupon_throwsCouponNotFoundException_whenCodeUnknown() {
        when(couponRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponService.redeemCoupon("UNKNOWN", REDEEMED_BY))
                .isInstanceOf(CouponNotFoundException.class);

        verify(couponRepository, never()).save(any());
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("redeemCoupon throws CouponExhaustedException and saves nothing when the coupon is exhausted")
    void redeemCoupon_throwsCouponExhaustedException_andSavesNothing_whenExhausted() {
        Coupon exhausted = new Coupon(CODE, 0);
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.of(exhausted));

        assertThatThrownBy(() -> couponService.redeemCoupon(CODE, REDEEMED_BY))
                .isInstanceOf(CouponExhaustedException.class);

        // The failed redemption is a no-op all the way up the stack: the
        // count on the (still in-memory) coupon didn't move, and neither
        // repository was ever asked to save anything.
        assertThat(exhausted.getRedemptionCount()).isZero();
        verify(couponRepository, never()).save(any());
        verify(redemptionRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5})
    @DisplayName("redeemCoupon succeeds exactly N times then fails, persisting exactly N times, not N+1")
    void redeemCoupon_exactBoundary_persistsExactlyNTimes(int maxRedemptions) {
        Coupon coupon = new Coupon(CODE, maxRedemptions);
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.of(coupon));
        when(redemptionRepository.save(any(Redemption.class))).thenAnswer(invocation -> invocation.getArgument(0));

        for (int i = 1; i <= maxRedemptions; i++) {
            couponService.redeemCoupon(CODE, REDEEMED_BY);
        }

        assertThatThrownBy(() -> couponService.redeemCoupon(CODE, REDEEMED_BY))
                .isInstanceOf(CouponExhaustedException.class);

        assertThat(coupon.getRedemptionCount()).isEqualTo(maxRedemptions);
        // Same "assertion precision" as CouponTest.redeem_succeedsExactlyNTimesThenFails,
        // now proving the persistence calls themselves stop exactly at N too -
        // the rejected (N+1)th attempt triggers zero additional save() calls
        // on either repository.
        verify(couponRepository, times(maxRedemptions)).save(coupon);
        verify(redemptionRepository, times(maxRedemptions)).save(any(Redemption.class));
    }

    // --- getRedemptionHistory ------------------------------------------

    @Test
    @DisplayName("getRedemptionHistory returns the repository's result for the coupon's id")
    void getRedemptionHistory_returnsRepositoryResult_whenCouponExists() {
        Coupon coupon = new Coupon(CODE, 5);
        List<Redemption> redemptions = List.of(new Redemption(coupon, REDEEMED_BY));
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.of(coupon));
        // coupon.getId() is null here - this coupon was never actually
        // persisted, so no database ever assigned it an id. The point of
        // this test isn't that the id is some specific value, it's that
        // whatever getCoupon(...).getId() returns is exactly what gets
        // passed to the repository - stubbing and calling with the same
        // (here, null) value proves that.
        when(redemptionRepository.findByCouponIdOrderByRedeemedAtAsc(coupon.getId())).thenReturn(redemptions);

        List<Redemption> result = couponService.getRedemptionHistory(CODE);

        assertThat(result).isEqualTo(redemptions);
    }

    @Test
    @DisplayName("getRedemptionHistory throws CouponNotFoundException without querying redemptions")
    void getRedemptionHistory_throwsCouponNotFoundException_whenCouponMissing() {
        when(couponRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponService.getRedemptionHistory("UNKNOWN"))
                .isInstanceOf(CouponNotFoundException.class);

        verify(redemptionRepository, never()).findByCouponIdOrderByRedeemedAtAsc(any());
    }
}
