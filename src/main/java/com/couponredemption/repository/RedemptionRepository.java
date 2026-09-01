package com.couponredemption.repository;

import com.couponredemption.domain.Redemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

    List<Redemption> findByCouponIdOrderByRedeemedAtAsc(Long couponId);

    // The literal "running count" this whole domain is about, as a query:
    // the number of Redemption rows actually persisted for a coupon, which
    // Coupon.redemptionCount should always equal.
    long countByCouponId(Long couponId);
}
