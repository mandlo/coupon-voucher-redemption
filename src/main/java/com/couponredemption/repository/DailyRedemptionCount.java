package com.couponredemption.repository;

import java.time.LocalDate;

/**
 * Projection for {@link RedemptionRepository#findDailyRedemptionCounts(Long)}:
 * how many redemptions a coupon received per calendar day, oldest first.
 */
public interface DailyRedemptionCount {

    LocalDate getDay();

    long getRedemptionCount();
}
