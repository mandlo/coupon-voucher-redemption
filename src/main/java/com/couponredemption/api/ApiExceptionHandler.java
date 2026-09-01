package com.couponredemption.api;

import com.couponredemption.api.dto.ErrorResponse;
import com.couponredemption.domain.CouponExhaustedException;
import com.couponredemption.service.exception.CouponNotFoundException;
import com.couponredemption.service.exception.DuplicateCouponCodeException;
import com.couponredemption.service.exception.InvalidRedeemerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// NEW TERM: @RestControllerAdvice (Spring Boot annotation)
// WHAT: Marks this class as a GLOBAL exception handler - its
//       @ExceptionHandler methods apply to every @RestController in the
//       application (right now, just CouponController, but any future
//       controller would automatically be covered too), not just one.
// WHY: Without this class, any of the exceptions below escaping a controller
//      method would turn into a bare 500 Internal Server Error with a raw
//      stack trace in the response body - the wrong status code for "coupon
//      not found" or "coupon already exhausted", and leaking internal detail
//      the client has no business seeing. This class is what turns
//      "the service threw a specific Java exception" into "the client
//      received the correct HTTP status and a clean JSON error body".
// WHEN: Once per application.
// HOW: Spring registers this as a bean and inspects every exception thrown
//      out of a controller method, routing it to whichever
//      @ExceptionHandler method below declares that exception's type.
@RestControllerAdvice
public class ApiExceptionHandler {

    // NEW TERM: @ExceptionHandler (Spring Boot annotation)
    // WHAT: Declares which exception type(s) this specific method handles.
    // WHY: Lets each KIND of failure map to the correct HTTP status in one
    //      place, instead of every controller method needing its own
    //      try/catch around every call to CouponService.
    // WHEN: One @ExceptionHandler method per group of exceptions that should
    //       map to the same HTTP status.
    // HOW: When CouponService.getCoupon (or getRedemptionHistory, or
    //      redeemCoupon) throws CouponNotFoundException, Spring intercepts
    //      it before it reaches the client and calls this method instead.
    @ExceptionHandler(CouponNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(CouponNotFoundException ex) {
        // NEW TERM: HTTP 404 Not Found
        // WHAT: The status code meaning "there is no resource at this URL".
        // WHY: A GET (or POST, for redeemCoupon) against a coupon code that
        //      doesn't exist is exactly this case.
        // WHEN: Whenever CouponService can't find a coupon by its code.
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * DuplicateCouponCodeException (createCoupon: the code is already taken)
     * and CouponExhaustedException (redeemCoupon: no redemptions left) are
     * grouped into one handler because they mean the same thing over HTTP:
     * the request is well-formed, but it conflicts with the current state of
     * the data.
     */
    @ExceptionHandler({DuplicateCouponCodeException.class, CouponExhaustedException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex) {
        // NEW TERM: HTTP 409 Conflict
        // WHAT: The status code meaning "the request is valid, but it can't
        //       be completed because of the resource's current state".
        // WHY: "This code is already registered" and "this coupon has no
        //      redemptions left" are both true statements about the data,
        //      not mistakes in how the request was written - that's exactly
        //      what 409 (as opposed to 400) communicates.
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * InvalidRedeemerException (redeemedBy isn't shaped like an email) and
     * IllegalArgumentException (thrown by Coupon's own constructor for a
     * blank code or a negative maxRedemptions) both mean the same thing over
     * HTTP: the request body itself was malformed.
     */
    @ExceptionHandler({InvalidRedeemerException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        // NEW TERM: HTTP 400 Bad Request
        // WHAT: The status code meaning "the client sent something the
        //       server can't process as-is".
        // WHY: A malformed redeemedBy, a blank code, or a negative
        //      maxRedemptions are all problems with what the CLIENT sent,
        //      not with the state of any stored data - that distinction is
        //      what separates 400 from 409 above.
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // Every handler method above ends up building the exact same RESPONSE
    // SHAPE (an ErrorResponse), just with a different status and message -
    // factored out here so that logic exists in exactly one place.
    //
    // NEW TERM: ResponseEntity<T> (Spring Boot class)
    // WHAT: A wrapper representing a full HTTP response: status code,
    //       headers, AND body, all together.
    // WHY: A plain return type (like CouponController's `CouponResponse`)
    //      only lets you control the BODY directly - the status code comes
    //      from @ResponseStatus instead. Here, the status varies per
    //      exception type, so it has to be set at runtime, which
    //      ResponseEntity is what supports.
    // HOW: ResponseEntity.status(status).body(errorResponse) builds one in a
    //      single fluent expression.
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), status.getReasonPhrase(), message));
    }
}
