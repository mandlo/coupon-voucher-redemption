package com.couponredemption.service.exception;

/**
 * Thrown when {@code redeemCoupon} is called with a {@code redeemedBy} value
 * that isn't shaped like an email address. This is a *format* rule owned by
 * the service layer, not the domain: {@code Redemption}'s own constructor
 * only rejects a blank/null value (see {@code Redemption.requireRedeemedBy}),
 * because the persistence layer has no opinion on what "who redeemed this"
 * should look like - deciding it must be an email address is a business
 * policy, and business policy belongs in the service layer.
 */
public class InvalidRedeemerException extends RuntimeException {

    private InvalidRedeemerException(String message) {
        super(message);
    }

    public static InvalidRedeemerException forValue(String redeemedBy) {
        return new InvalidRedeemerException(
                "\"" + redeemedBy + "\" is not a valid redeemer identifier (expected an email address)");
    }
}
