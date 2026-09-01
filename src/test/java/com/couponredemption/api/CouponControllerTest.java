package com.couponredemption.api;

import com.couponredemption.api.dto.CreateCouponRequest;
import com.couponredemption.api.dto.RedeemCouponRequest;
import com.couponredemption.domain.Coupon;
import com.couponredemption.domain.CouponExhaustedException;
import com.couponredemption.domain.Redemption;
import com.couponredemption.service.CouponService;
import com.couponredemption.service.exception.CouponNotFoundException;
import com.couponredemption.service.exception.DuplicateCouponCodeException;
import com.couponredemption.service.exception.InvalidRedeemerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// NEW TERM: @WebMvcTest (Spring Boot Test annotation)
// WHAT: Starts a MINIMAL Spring context containing only the web layer -
//       @RestController, @RestControllerAdvice, Jackson JSON conversion,
//       and MockMvc - for JUST the controller class named in parentheses.
//       It does NOT start a real HTTP server, does NOT load
//       @Service/@Repository beans, and does NOT connect to a database.
// WHY: This is Spring's own "controller unit test" facility: it proves
//      CouponController's routing, request/response JSON handling, and
//      ApiExceptionHandler's status-code mapping all work together
//      correctly, without paying the cost (or needing the database) of a
//      full @SpringBootTest. That's exactly why it isn't an "integration
//      test" in the sense this project avoids - no database, no full
//      application context, no other layer's real implementation involved.
// WHEN: For testing exactly one @RestController's behavior in isolation.
// HOW: `@WebMvcTest(CouponController.class)` tells Spring "only wire up
//      this controller (and any @RestControllerAdvice, like
//      ApiExceptionHandler, which applies globally)". CouponController
//      still needs a CouponService bean to be constructed - @MockBean below
//      supplies a fake one.
@WebMvcTest(CouponController.class)
class CouponControllerTest {

    // NEW TERM: MockMvc (Spring Test class)
    // WHAT: A test client that can build and "send" HTTP requests to
    //       CouponController WITHOUT starting a real servlet container or
    //       opening a real network port.
    // WHY: Lets a test write `mockMvc.perform(post("/api/coupons")...)` and
    //      get back a real HTTP status code + JSON body to assert on, as if
    //      a real client had called the API.
    // WHEN: Used in every test method below to actually exercise an endpoint.
    // HOW: @WebMvcTest auto-configures a MockMvc bean wired to
    //      CouponController + ApiExceptionHandler; @Autowired injects it.
    @Autowired
    private MockMvc mockMvc;

    // NEW TERM: ObjectMapper (Jackson class)
    // WHAT: The JSON <-> Java object converter Spring Boot uses internally
    //       for every @RequestBody/@ResponseBody. @WebMvcTest auto-configures
    //       one as a bean.
    // WHY: Test methods need to turn a Java object (e.g. a
    //      CreateCouponRequest) into a JSON string to put in a fake
    //      request body - writeValueAsString does exactly that.
    // WHEN: Any time a test needs to POST a JSON body.
    @Autowired
    private ObjectMapper objectMapper;

    // NEW TERM: @MockBean (Spring Boot Test annotation)
    // WHAT: Replaces the real CouponService bean inside this test's Spring
    //       context with a Mockito mock.
    // WHY: CouponController's constructor requires a CouponService - without
    //      a bean of that type, the context couldn't even start. @MockBean
    //      supplies a fake one, so this test proves CouponController's OWN
    //      behavior (routing, status codes, JSON shapes) without a real
    //      service, and therefore without a real repository or database
    //      underneath it either.
    // WHEN: Any dependency a @WebMvcTest-scoped controller needs but that
    //       the test itself wants to control with when(...)/verify(...).
    // HOW: Functions exactly like the @Mock fields in CouponServiceTest -
    //      the difference is @Mock creates a plain Mockito mock, while
    //      @MockBean creates one AND registers it in the Spring context so
    //      Spring can inject it into CouponController for us.
    @MockBean
    private CouponService couponService;

    private static final String CODE = "SUMMER10";

    // --- POST /api/coupons (createCoupon) -----------------------------

    @Test
    @DisplayName("POST /api/coupons returns 201 and the created coupon")
    void createCoupon_returns201AndBody() throws Exception {
        when(couponService.createCoupon(CODE, 5)).thenReturn(new Coupon(CODE, 5));

        // NEW TERM: mockMvc.perform(...) (Spring Test method)
        // WHAT: Sends one simulated HTTP request through CouponController.
        // HOW: `post("/api/coupons")` builds a POST request to that path;
        //      .contentType(...) sets the Content-Type header; .content(...)
        //      supplies the raw request body (JSON text, built here via
        //      objectMapper.writeValueAsString on a real CreateCouponRequest).
        mockMvc.perform(post("/api/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCouponRequest(CODE, 5))))
                // NEW TERM: .andExpect(...) (Spring Test method)
                // WHAT: Asserts something about the response that came back
                //       from perform(...) - if the assertion fails, the test
                //       fails with a clear message showing the actual vs
                //       expected value.
                // HOW: Multiple .andExpect(...) calls can be chained; each
                //      checks one thing about the same response.
                //
                // NEW TERM: status().isCreated() (Spring Test method)
                // WHAT: Asserts the response's HTTP status code is 201.
                // WHY: Confirms CouponController's @ResponseStatus(HttpStatus.CREATED)
                //      on createCoupon actually took effect.
                .andExpect(status().isCreated())
                // NEW TERM: jsonPath("$.field") (Spring Test / JsonPath)
                // WHAT: Reads one field out of the response body's JSON using
                //       a small path expression ("$" means "the JSON root").
                // WHY: Lets a test assert on individual fields of the JSON
                //      response without manually parsing the whole body.
                // HOW: jsonPath("$.code").value("SUMMER10") checks that the
                //      response JSON has {"code": "SUMMER10", ...}.
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.maxRedemptions").value(5))
                .andExpect(jsonPath("$.redemptionCount").value(0))
                .andExpect(jsonPath("$.exhausted").value(false));

        // Confirms the controller actually called the service with the
        // request body's own values, not hardcoded ones.
        verify(couponService).createCoupon(CODE, 5);
    }

    @Test
    @DisplayName("POST /api/coupons returns 409 when the code is already taken")
    void createCoupon_returns409_whenCodeAlreadyExists() throws Exception {
        when(couponService.createCoupon(CODE, 5)).thenThrow(DuplicateCouponCodeException.forCode(CODE));

        mockMvc.perform(post("/api/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCouponRequest(CODE, 5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(CODE)));
    }

    @Test
    @DisplayName("POST /api/coupons returns 400 when the service rejects the input as invalid")
    void createCoupon_returns400_whenServiceRejectsInvalidInput() throws Exception {
        // This simulates Coupon's own constructor rejecting a blank code:
        // CouponService.createCoupon would let that IllegalArgumentException
        // propagate unchanged, so the mock is told to do the same. This test
        // proves ApiExceptionHandler.handleBadRequest correctly maps a plain
        // IllegalArgumentException to 400 - it does NOT re-test Coupon's own
        // validation logic, which CouponTest already covers.
        when(couponService.createCoupon("", 5))
                .thenThrow(new IllegalArgumentException("Coupon code must not be blank"));

        mockMvc.perform(post("/api/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCouponRequest("", 5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // --- GET /api/coupons/{code} (getCoupon) -----------------------------

    @Test
    @DisplayName("GET /api/coupons/{code} returns 200 and the coupon when it exists")
    void getCoupon_returns200AndBody_whenFound() throws Exception {
        when(couponService.getCoupon(CODE)).thenReturn(new Coupon(CODE, 5));

        mockMvc.perform(get("/api/coupons/{code}", CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.remainingRedemptions").value(5));
    }

    @Test
    @DisplayName("GET /api/coupons/{code} returns 404 when the coupon doesn't exist")
    void getCoupon_returns404_whenMissing() throws Exception {
        when(couponService.getCoupon("UNKNOWN")).thenThrow(CouponNotFoundException.forCode("UNKNOWN"));

        mockMvc.perform(get("/api/coupons/{code}", "UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // --- GET /api/coupons/{code}/redemptions (getRedemptionHistory) -----

    @Test
    @DisplayName("GET /api/coupons/{code}/redemptions returns 200 and the redemption list")
    void getRedemptionHistory_returns200AndBody() throws Exception {
        Coupon coupon = new Coupon(CODE, 5);
        when(couponService.getRedemptionHistory(CODE))
                .thenReturn(List.of(new Redemption(coupon, "customer@example.com")));

        mockMvc.perform(get("/api/coupons/{code}/redemptions", CODE))
                .andExpect(status().isOk())
                // $[0] means "the first element of the JSON array" -
                // getRedemptionHistory returns a List, which Jackson
                // serializes as a JSON array, so this indexes into it.
                .andExpect(jsonPath("$[0].redeemedBy").value("customer@example.com"));
    }

    @Test
    @DisplayName("GET /api/coupons/{code}/redemptions returns 404 when the coupon doesn't exist")
    void getRedemptionHistory_returns404_whenMissing() throws Exception {
        when(couponService.getRedemptionHistory("UNKNOWN")).thenThrow(CouponNotFoundException.forCode("UNKNOWN"));

        mockMvc.perform(get("/api/coupons/{code}/redemptions", "UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    // --- POST /api/coupons/{code}/redemptions (redeemCoupon) ------------

    @Test
    @DisplayName("POST /api/coupons/{code}/redemptions returns 201 and the created redemption")
    void redeemCoupon_returns201AndBody_onSuccess() throws Exception {
        Coupon coupon = new Coupon(CODE, 5);
        when(couponService.redeemCoupon(CODE, "customer@example.com"))
                .thenReturn(new Redemption(coupon, "customer@example.com"));

        mockMvc.perform(post("/api/coupons/{code}/redemptions", CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RedeemCouponRequest("customer@example.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.redeemedBy").value("customer@example.com"));

        verify(couponService).redeemCoupon(CODE, "customer@example.com");
    }

    @Test
    @DisplayName("POST /api/coupons/{code}/redemptions returns 404 when the coupon doesn't exist")
    void redeemCoupon_returns404_whenCouponMissing() throws Exception {
        when(couponService.redeemCoupon("UNKNOWN", "customer@example.com"))
                .thenThrow(CouponNotFoundException.forCode("UNKNOWN"));

        mockMvc.perform(post("/api/coupons/{code}/redemptions", "UNKNOWN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RedeemCouponRequest("customer@example.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/coupons/{code}/redemptions returns 409 when the coupon is exhausted")
    void redeemCoupon_returns409_whenCouponExhausted() throws Exception {
        when(couponService.redeemCoupon(CODE, "customer@example.com"))
                .thenThrow(CouponExhaustedException.forCoupon(CODE, 5));

        mockMvc.perform(post("/api/coupons/{code}/redemptions", CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RedeemCouponRequest("customer@example.com"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/coupons/{code}/redemptions returns 400 when redeemedBy is malformed")
    void redeemCoupon_returns400_whenRedeemedByInvalid() throws Exception {
        when(couponService.redeemCoupon(CODE, "not-an-email"))
                .thenThrow(InvalidRedeemerException.forValue("not-an-email"));

        mockMvc.perform(post("/api/coupons/{code}/redemptions", CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RedeemCouponRequest("not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Belt-and-braces: whatever the outcome, redeemCoupon was called
        // exactly once with exactly these arguments - proves the controller
        // forwarded the path variable and request body field unchanged,
        // rather than e.g. swapping them or hardcoding a different value.
        verify(couponService).redeemCoupon(CODE, "not-an-email");
        verify(couponService, times(1)).redeemCoupon(any(), any());
    }
}
