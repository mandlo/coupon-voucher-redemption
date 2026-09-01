package com.couponredemption.api;

import com.couponredemption.api.dto.CouponResponse;
import com.couponredemption.api.dto.CreateCouponRequest;
import com.couponredemption.api.dto.RedeemCouponRequest;
import com.couponredemption.api.dto.RedemptionResponse;
import com.couponredemption.domain.Coupon;
import com.couponredemption.domain.Redemption;
import com.couponredemption.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// NEW TERM: @RestController (Spring Boot annotation)
// WHAT: Marks this class as a web controller whose method return values are
//       written directly into the HTTP response body as JSON (Jackson does
//       the Java-object-to-JSON conversion automatically), rather than being
//       resolved as a view/template name. It's shorthand for @Controller
//       plus @ResponseBody on every method.
// WHY: Without it, Spring has no way to know this class should handle HTTP
//      requests at all - it would just be an ordinary, unused Java class.
// WHEN: Once per class that exposes REST endpoints. This is the FIRST
//       controller in this project - everything before this layer
//       (domain/repository/service) had no awareness of HTTP at all.
// HOW: Spring scans the application for @RestController-annotated classes at
//      startup and registers their @GetMapping/@PostMapping methods as
//      routes.
//
// NEW TERM: @RequestMapping (Spring Boot annotation)
// WHAT: Sets a base URL path shared by every endpoint method in this class.
// WHY: Avoids repeating "/api/coupons" on every single @GetMapping/@PostMapping.
// WHEN: Once per controller, at the class level.
// HOW: A request to "/api/coupons/SUMMER10" is matched by combining this
//      class-level path ("/api/coupons") with a method-level path ("/{code}").
@RestController
@RequestMapping("/api/coupons")
// NEW TERM: @Tag (springdoc-openapi / Swagger annotation)
// WHAT: Groups this controller's endpoints under one named section in the
//       Swagger UI page.
// WHY: Purely cosmetic/organizational - it makes the generated API
//      documentation easier to read once there's more than one controller.
// WHEN: Once per controller.
// HOW: springdoc-openapi (already a dependency - see pom.xml and
//      OpenApiConfig) scans for this annotation when building the OpenAPI
//      spec that Swagger UI renders.
@Tag(name = "Coupons", description = "Create coupons, look them up, and redeem them")
public class CouponController {

    // Constructor injection, same reasoning as CouponService's own
    // constructor: makes this class trivial to unit test by passing in a
    // Mockito mock, with no Spring context required (see CouponControllerTest).
    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    // NEW TERM: @PostMapping (Spring Boot annotation)
    // WHAT: Routes an HTTP POST request to this method.
    // WHY: POST is the conventional HTTP method for "create a new resource" -
    //      the client doesn't know the new coupon's id/code in advance the
    //      way it would for an update.
    // WHEN: Used here for both "create a coupon" and "redeem a coupon",
    //       because both actions create something new (a Coupon row, or a
    //       Redemption row) rather than replacing or removing one.
    // HOW: Combined with @RequestMapping("/api/coupons") above, this method
    //      handles "POST /api/coupons".
    @PostMapping
    // NEW TERM: @ResponseStatus (Spring Boot annotation)
    // WHAT: Sets the HTTP status code returned when this method completes
    //       normally (i.e. doesn't throw).
    // WHY: Without it, Spring defaults every successful @RestController
    //      method to 200 OK. 201 Created is the more correct status for
    //      "a new resource now exists" - it tells the client something was
    //      actually created, not just read.
    // WHEN: On any endpoint that creates a new resource (here: createCoupon
    //       and redeemCoupon, since a redemption is itself a new resource).
    // HOW: HttpStatus.CREATED is an enum constant representing the number 201.
    @ResponseStatus(HttpStatus.CREATED)
    // NEW TERM: @Operation (springdoc-openapi / Swagger annotation)
    // WHAT: A one-line human-readable summary shown in Swagger UI for this
    //       specific endpoint.
    // WHY: Without it, Swagger UI would still list the endpoint (path +
    //      method), just with no description of what it does.
    // WHEN: One per endpoint method.
    @Operation(summary = "Create a new coupon with a fixed redemption limit")
    public CouponResponse createCoupon(
            // NEW TERM: @RequestBody (Spring Boot annotation)
            // WHAT: Tells Spring to deserialize the incoming HTTP request
            //       body's JSON into this method parameter.
            // WHY: This is how the client's JSON (e.g.
            //      {"code":"SUMMER10","maxRedemptions":5}) becomes a real
            //      Java object (a CreateCouponRequest) that the method body
            //      can call .code()/.maxRedemptions() on.
            // WHEN: On any endpoint that accepts a JSON request body -
            //       typically POST/PUT, never on a simple GET.
            // HOW: Spring Boot's auto-configured Jackson ObjectMapper reads
            //      the raw bytes of the HTTP body and maps each JSON field
            //      onto the matching record component by name.
            @RequestBody CreateCouponRequest request) {
        // The controller does NOT validate code/maxRedemptions itself, and it
        // does NOT construct a Coupon itself - both of those already happen
        // inside CouponService.createCoupon (which delegates to `new Coupon(...)`).
        // This method's entire job is: unpack the DTO, call the service,
        // wrap the result back into a DTO. No business logic lives here.
        Coupon coupon = couponService.createCoupon(request.code(), request.maxRedemptions());
        return CouponResponse.from(coupon);
    }

    // NEW TERM: @GetMapping (Spring Boot annotation)
    // WHAT: Routes an HTTP GET request to this method.
    // WHY: GET is the conventional HTTP method for "read data, no side
    //      effects" - looking up a coupon doesn't change anything, so GET
    //      (not POST) is the correct verb.
    // HOW: "/{code}" here, combined with the class-level "/api/coupons",
    //      matches "GET /api/coupons/SUMMER10".
    @GetMapping("/{code}")
    @Operation(summary = "Get a coupon by its code")
    public CouponResponse getCoupon(
            // NEW TERM: @PathVariable (Spring Boot annotation)
            // WHAT: Binds a placeholder from the URL path (the "{code}" in
            //       "/{code}" above) to this method parameter.
            // WHY: The coupon's code IS the URL - "/api/coupons/SUMMER10"
            //      identifies exactly one resource, the same way a file path
            //      identifies exactly one file.
            // WHEN: For any part of the URL that names a specific resource
            //       (as opposed to a query parameter like "?term=...", used
            //       for things that filter/search rather than identify).
            // HOW: Spring matches the "{code}" placeholder's name to this
            //      parameter's name automatically, since they're spelled the
            //      same ("code").
            @PathVariable String code) {
        return CouponResponse.from(couponService.getCoupon(code));
    }

    @GetMapping("/{code}/redemptions")
    @Operation(summary = "List every redemption recorded for a coupon, oldest first")
    public List<RedemptionResponse> getRedemptionHistory(@PathVariable String code) {
        // .stream().map(...).toList() converts each domain Redemption into
        // its RedemptionResponse DTO - the list itself never contains a raw
        // entity, only DTOs, all the way out to the JSON response.
        return couponService.getRedemptionHistory(code).stream()
                .map(RedemptionResponse::from)
                .toList();
    }

    @PostMapping("/{code}/redemptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Redeem one use of a coupon")
    public RedemptionResponse redeemCoupon(@PathVariable String code, @RequestBody RedeemCouponRequest request) {
        // Every real decision - does this coupon exist, is redeemedBy a
        // valid email, is the coupon exhausted - happens inside
        // CouponService.redeemCoupon (and, beneath that, Coupon.redeem()).
        // If any of those checks fail, redeemCoupon throws, this method
        // never reaches its return statement, and ApiExceptionHandler
        // (registered globally - see that class) converts the exception
        // into the correct HTTP status before a response ever reaches the
        // client.
        Redemption redemption = couponService.redeemCoupon(code, request.redeemedBy());
        return RedemptionResponse.from(redemption);
    }
}
