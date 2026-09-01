package com.couponredemption.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The audit trail of one successful redemption: who redeemed a coupon, and
 * when. This entity does not enforce the redemption limit itself - that is
 * entirely {@link Coupon#redeem()}'s job - it only records the fact that a
 * redemption already happened. Wiring "call coupon.redeem(), then save a
 * Redemption for it" into one atomic operation is service-layer work for a
 * later lesson; this layer only models the two things being stored.
 * <p>
 * {@code redeemedAt} is stamped in the constructor with {@code Instant.now()}
 * rather than a Hibernate {@code @CreationTimestamp}, on purpose: this
 * project's tests are unit tests only, with no database and no Spring
 * context, and {@code @CreationTimestamp} only populates on an actual
 * persist. Stamping it in Java means a plain {@code new Redemption(...)} is
 * enough to assert the timestamp was set, with nothing to mock.
 * <p>
 * Like {@code Loan} in the library-lending lesson this series is modeled
 * after, this entity intentionally does not override {@code equals()}/
 * {@code hashCode()} - there's no natural single-field business key for "one
 * redemption event", and falling back to identity equality is the right
 * default for an entity that is never stored in a Set/Map before being
 * persisted.
 */
@Entity
@Table(name = "redemptions")
public class Redemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Column(name = "redeemed_by", nullable = false, length = 150)
    private String redeemedBy;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;

    protected Redemption() {
    }

    public Redemption(Coupon coupon, String redeemedBy) {
        this.coupon = requireCoupon(coupon);
        this.redeemedBy = requireRedeemedBy(redeemedBy);
        this.redeemedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Coupon getCoupon() {
        return coupon;
    }

    public String getRedeemedBy() {
        return redeemedBy;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }

    private static Coupon requireCoupon(Coupon value) {
        if (value == null) {
            throw new IllegalArgumentException("Redemption must reference a coupon");
        }
        return value;
    }

    private static String requireRedeemedBy(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("redeemedBy must not be blank");
        }
        return value.trim();
    }
}
