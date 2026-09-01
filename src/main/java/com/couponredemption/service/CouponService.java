package com.couponredemption.service;

import com.couponredemption.domain.Coupon;
import com.couponredemption.domain.CouponExhaustedException;
import com.couponredemption.domain.Redemption;
import com.couponredemption.repository.CouponRepository;
import com.couponredemption.repository.RedemptionRepository;
import com.couponredemption.service.exception.CouponNotFoundException;
import com.couponredemption.service.exception.DuplicateCouponCodeException;
import com.couponredemption.service.exception.InvalidRedeemerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

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

    // A Pattern is a compiled regular expression. Compiling is the
    // relatively expensive part of using a regex, so it's done ONCE, here,
    // when the class is loaded - not every time redeemCoupon() runs. That's
    // why this is `static` (one shared copy, not one per CouponService
    // instance) and `final` (the reference can never be reassigned).
    //
    // The pattern itself, read left to right:
    //   ^                       start of the string
    //   [A-Za-z0-9._%+-]+       one or more "local part" characters (before the @)
    //   @                       a literal @
    //   [A-Za-z0-9.-]+          one or more domain characters (e.g. "example")
    //   \.                      a literal dot (escaped, because a bare "."
    //                           in a regex means "any character")
    //   [A-Za-z]{2,}            the top-level domain: 2 or more letters (e.g. "com")
    //   $                       end of the string
    // This is a deliberately simple, "good enough for a learning project"
    // email check - real-world email validation is notoriously more
    // complicated than any single regex can fully capture.
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

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
     * @throws InvalidRedeemerException  if redeemedBy isn't shaped like an email address
     * @throws CouponNotFoundException   if no coupon has this code
     * @throws CouponExhaustedException  if the coupon has no redemptions left
     */
    @Transactional
    public Redemption redeemCoupon(String code, String redeemedBy) {
        // Validate the INPUT FORMAT first, before doing any repository work
        // at all - not even the "does this coupon exist?" lookup runs yet.
        // This is the same "fail fast" idea already used in createCoupon()
        // (duplicate-code check before construction): the cheapest possible
        // check runs first, so a bad request never wastes a database call.
        requireValidRedeemer(redeemedBy);
        Coupon coupon = findCouponOrThrow(code);
        coupon.redeem();
        couponRepository.save(coupon);
        return redemptionRepository.save(new Redemption(coupon, redeemedBy));
    }

    private Coupon findCouponOrThrow(String code) {
        return couponRepository.findByCode(code)
                .orElseThrow(() -> CouponNotFoundException.forCode(code));
    }

    // A private helper, same style as Coupon's own requireCode/requireNonNegative:
    // a small method whose only job is "throw if invalid, otherwise do nothing."
    private static void requireValidRedeemer(String redeemedBy) {
        // redeemedBy == null is checked FIRST, and Java's `||` (logical OR)
        // short-circuits: if the left side is true, the right side is never
        // evaluated. That matters here, because calling .matcher(...) on a
        // null String would throw a NullPointerException - this ordering
        // avoids that entirely rather than needing a try/catch.
        if (redeemedBy == null || !EMAIL_PATTERN.matcher(redeemedBy).matches()) {
            throw InvalidRedeemerException.forValue(redeemedBy);
        }
    }
}
