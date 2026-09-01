package com.couponredemption.service;

import com.couponredemption.domain.Coupon;
import com.couponredemption.domain.CouponExhaustedException;
import com.couponredemption.domain.Redemption;
import com.couponredemption.repository.CouponRepository;
import com.couponredemption.repository.RedemptionRepository;
import com.couponredemption.service.exception.CouponNotFoundException;
import com.couponredemption.service.exception.DuplicateCouponCodeException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrates the one operation the persistence layer only modeled the
 * pieces of: redeeming a coupon. {@link Coupon#redeem()} already does the
 * actual boundary check and increment, and {@link Redemption}'s constructor
 * already stamps who/when - this service's entire job is to look the coupon
 * up by code, call both in the right order inside one transaction, and
 * translate "no such coupon" into a real exception instead of a
 * {@code NullPointerException} or an unchecked {@code Optional.get()}.
 * <p>
 * Constructor injection (not field injection) is what makes this trivial to
 * unit test with plain Mockito mocks and no Spring context at all.
 */
@Service
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;
    private final RedemptionRepository redemptionRepository;

    public CouponService(CouponRepository couponRepository, RedemptionRepository redemptionRepository) {
        this.couponRepository = couponRepository;
        this.redemptionRepository = redemptionRepository;
    }

    /**
     * Registers a new coupon, provided its code isn't already taken.
     * The duplicate check runs before the {@link Coupon} is even
     * constructed, so a taken code never reaches a save call.
     *
     * @throws DuplicateCouponCodeException if a coupon with this code already exists
     */
    @Transactional
    public Coupon createCoupon(String code, int maxRedemptions) {
        if (couponRepository.findByCode(code).isPresent()) {
            throw DuplicateCouponCodeException.forCode(code);
        }
        return couponRepository.save(new Coupon(code, maxRedemptions));
    }

    /**
     * @throws CouponNotFoundException if no coupon has this code
     */
    public Coupon getCoupon(String code) {
        return findCouponOrThrow(code);
    }

    /**
     * Every redemption recorded for a coupon, oldest first.
     *
     * @throws CouponNotFoundException if no coupon has this code
     */
    public List<Redemption> getRedemptionHistory(String code) {
        Coupon coupon = findCouponOrThrow(code);
        return redemptionRepository.findByCouponIdOrderByRedeemedAtAsc(coupon.getId());
    }

    /**
     * Redeems one use of a coupon: look it up, let {@link Coupon#redeem()}
     * decide whether there's room left, and only on success save both the
     * coupon's new count and a {@link Redemption} audit row, as one
     * transaction.
     * <p>
     * If the coupon is exhausted, {@link Coupon#redeem()} throws
     * {@link CouponExhaustedException} before either {@code save} call below
     * runs - this method makes no attempt to catch or translate it, so a
     * failed redemption leaves nothing persisted, exactly like a direct call
     * to {@code Coupon.redeem()} does at the domain layer.
     *
     * @throws CouponNotFoundException   if no coupon has this code
     * @throws CouponExhaustedException  if the coupon has no redemptions left
     */
    @Transactional
    public Redemption redeemCoupon(String code, String redeemedBy) {
        Coupon coupon = findCouponOrThrow(code);
        coupon.redeem();
        couponRepository.save(coupon);
        return redemptionRepository.save(new Redemption(coupon, redeemedBy));
    }

    private Coupon findCouponOrThrow(String code) {
        return couponRepository.findByCode(code)
                .orElseThrow(() -> CouponNotFoundException.forCode(code));
    }
}
