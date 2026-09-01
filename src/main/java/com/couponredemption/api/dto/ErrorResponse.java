package com.couponredemption.api.dto;

import java.time.Instant;

// The single JSON shape every error from ApiExceptionHandler comes back as -
// a 404, a 409, and a 400 all use this same record, just with different
// field values. Having ONE error shape (rather than a different one per
// exception type) is what makes the API predictable to a client: "check
// response.status, read response.message" always works, regardless of which
// endpoint or which failure produced it.
public record ErrorResponse(int status, String error, String message, Instant timestamp) {

    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, Instant.now());
    }
}
