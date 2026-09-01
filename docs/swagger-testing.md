# Manual API Testing with Swagger UI

This walks through testing the live API by hand, through the browser-based Swagger UI, using this application's actual coupon-redemption endpoints and real example data. There are no automated Swagger/OpenAPI tests in this repository on purpose (see [`CouponControllerTest`](../src/test/java/com/couponredemption/api/CouponControllerTest.java) for the automated coverage instead, and [README.md](../README.md#api-testing--swagger) for why) — this document is how a human verifies the same behavior interactively.

## 1. Start the application

Swagger UI is served *by* the running Spring Boot application, so the app has to actually be up first. Unlike `mvn test` (which needs no database at all — see the main [README](../README.md)), running the app for real needs Postgres:

```bash
createdb coupon_redemption
createuser coupon_redemption --pwprompt   # set the password to "coupon_redemption", or override via env vars below

DB_USERNAME=coupon_redemption DB_PASSWORD=coupon_redemption mvn spring-boot:run
```

Wait for a line like `Started CouponVoucherRedemptionApplication in N.NNN seconds` in the console — that's the signal the embedded web server (on port 8080 by default, see `application.yml`) is ready to accept requests.

## 2. Open Swagger UI

With the app running, open:

```
http://localhost:8080/swagger-ui.html
```

in a browser. This page is generated automatically by the `springdoc-openapi-starter-webmvc-ui` dependency (see `pom.xml`) by scanning every `@RestController` at startup — nobody hand-writes this page. The raw machine-readable spec it's built from is also available directly, if you ever need it (e.g. to import into Postman): `http://localhost:8080/v3/api-docs`.

## 3. Identify the available endpoints

Swagger UI groups endpoints by the `@Tag` each controller declares (see `CouponController`). This application has one tag, **"Coupons"** — click it to expand the group. You should see four rows, each showing an HTTP method badge (color-coded), a path, and the one-line `@Operation` summary from the controller:

| Method | Path | Summary |
|---|---|---|
| `POST` | `/api/coupons` | Create a new coupon with a fixed redemption limit |
| `GET` | `/api/coupons/{code}` | Get a coupon by its code |
| `GET` | `/api/coupons/{code}/redemptions` | List every redemption recorded for a coupon, oldest first |
| `POST` | `/api/coupons/{code}/redemptions` | Redeem one use of a coupon |

## 4. Authentication

**None.** This application has no security configuration at all — every endpoint above is open, with no API key, token, or login required. Click any row and you'll go straight to the "Try it out" button with no auth prompt in between.

## 5. Using "Try it out"

Click any endpoint row to expand it, then click the **"Try it out"** button in the top-right of the expanded panel. This switches every field from read-only documentation into an actual editable form — parameter fields become text boxes, and any request body becomes an editable JSON textarea pre-filled with an example. Fill in the values (see the worked examples below), then click the blue **"Execute"** button. Swagger UI will show, in order: the exact `curl` command it sent, the response status code, the response body, and the response headers — everything needed to see exactly what happened.

## 6. Worked example: the full success path

Try these four calls in order — they tell one continuous story, using the same coupon code throughout.

### 6a. Create a coupon — `POST /api/coupons`

No path/query parameters — only a request body. Example:

```json
{
  "code": "SUMMER10",
  "maxRedemptions": 2
}
```

Click Execute. Expected result: **`201 Created`**, with a body like:

```json
{
  "id": 1,
  "code": "SUMMER10",
  "maxRedemptions": 2,
  "redemptionCount": 0,
  "remainingRedemptions": 2,
  "exhausted": false
}
```

`id` will differ depending on what's already in your database — everything else should match exactly.

### 6b. Look it up — `GET /api/coupons/{code}`

Required path parameter: `code`. Enter `SUMMER10`. Expected result: **`200 OK`**, with the same body shape as above.

### 6c. Redeem it — `POST /api/coupons/{code}/redemptions`

Path parameter `code` = `SUMMER10`. Request body:

```json
{
  "redeemedBy": "customer@example.com"
}
```

Expected result: **`201 Created`**, with a body like:

```json
{
  "id": 1,
  "couponId": 1,
  "redeemedBy": "customer@example.com",
  "redeemedAt": "2026-09-01T12:34:56.789Z"
}
```

Run this **exact same call again** (same code, same or different `redeemedBy`) — it's still `201 Created`, and `GET /api/coupons/SUMMER10` now shows `"redemptionCount": 2, "remainingRedemptions": 0, "exhausted": true`. This coupon had `maxRedemptions: 2`, so it just hit its boundary — exactly the "Nth redemption succeeds" case this whole application is built around.

### 6d. View the redemption history — `GET /api/coupons/{code}/redemptions`

Path parameter `code` = `SUMMER10`. Expected result: **`200 OK`**, with a JSON array of both redemptions from step 6c, oldest first:

```json
[
  { "id": 1, "couponId": 1, "redeemedBy": "customer@example.com", "redeemedAt": "2026-09-01T12:34:56.789Z" },
  { "id": 2, "couponId": 1, "redeemedBy": "customer@example.com", "redeemedAt": "2026-09-01T12:35:10.456Z" }
]
```

## 7. Testing validation / error scenarios

This is where you deliberately trigger every failure case and check the API responds the way `CouponControllerTest` already proves it does — now watching it happen for real, against a real database.

Every error below comes back in the same JSON shape (see `ErrorResponse`):

```json
{
  "status": 409,
  "error": "Conflict",
  "message": "...",
  "timestamp": "2026-09-01T12:40:00.000Z"
}
```

Only `status`, `error`, and `message` change per scenario.

### 7a. Redeem an already-exhausted coupon → `409 Conflict`

Repeat step 6c a **third** time against `SUMMER10` (which already used both of its 2 redemptions). Expected: **`409 Conflict`**, with `"message"` containing something like `Coupon SUMMER10 has already reached its redemption limit of 2`.

### 7b. Create a coupon with a code that's already taken → `409 Conflict`

Repeat step 6a with the exact same body (`"code": "SUMMER10"`) a second time. Expected: **`409 Conflict`**, `"message"` containing `A coupon with code SUMMER10 already exists`.

### 7c. Look up (or redeem) a coupon code that doesn't exist → `404 Not Found`

Try `GET /api/coupons/DOES-NOT-EXIST`. Expected: **`404 Not Found`**, `"message"` containing `No coupon found with code DOES-NOT-EXIST`. The same happens for `GET .../redemptions` and `POST .../redemptions` against an unknown code.

### 7d. Redeem with a malformed `redeemedBy` → `400 Bad Request`

Create a fresh coupon first (a new code, e.g. `WINTER5`, `maxRedemptions: 5`), then try redeeming it with:

```json
{
  "redeemedBy": "not-an-email"
}
```

Expected: **`400 Bad Request`**, `"message"` containing `"not-an-email" is not a valid redeemer identifier (expected an email address)`.

### 7e. Create a coupon with a blank code → `400 Bad Request`

```json
{
  "code": "",
  "maxRedemptions": 5
}
```

Expected: **`400 Bad Request`**, `"message"` containing `Coupon code must not be blank`. (This is `Coupon`'s own constructor validation, from the domain layer — the API layer doesn't duplicate this check, it just reports whatever the service throws. See `docs/queries.md` and the main README for how validation is layered across this application.)

### 7f. Create a coupon with a negative redemption limit → `400 Bad Request`

```json
{
  "code": "NEGATIVE1",
  "maxRedemptions": -1
}
```

Expected: **`400 Bad Request`**, `"message"` containing `Max redemptions must not be negative`. Same validation source as 7e — `Coupon`'s constructor (see `CouponTest.negativeMaxRedemptions_isRejected`).

### 7g. Redeem with other malformed `redeemedBy` shapes → `400 Bad Request`

`CouponServiceTest` and `CouponControllerTest` both exercise several malformed shapes, not just one — worth trying more than one by hand too. Using the `WINTER5` coupon from 7d, any of these bodies should produce the same `400`:

```json
{ "redeemedBy": "missing-at-sign.com" }
```
```json
{ "redeemedBy": "double@@example.com" }
```
```json
{ "redeemedBy": "trailing-dot@example." }
```
```json
{ "redeemedBy": "" }
```

The last one (`""`) fails the same regex check as the others (an empty string has no `@`), so it also comes back `400`, not some other status — there's no separate "blank" rule at the API layer, just the one email-shape check in `CouponService.requireValidRedeemer`.

## 8. Quick-reference cheat sheet

Every scenario above, in one table — copy a request body straight into Swagger UI's "Try it out" textarea.

| # | Endpoint | Request body | Expected status | Key response field |
|---|---|---|---|---|
| 6a | `POST /api/coupons` | `{"code":"SUMMER10","maxRedemptions":2}` | `201` | `"exhausted": false` |
| 6b | `GET /api/coupons/SUMMER10` | — | `200` | `"remainingRedemptions": 2` |
| 6c | `POST /api/coupons/SUMMER10/redemptions` | `{"redeemedBy":"customer@example.com"}` | `201` (×2, then see 7a) | `"redeemedBy": "customer@example.com"` |
| 6d | `GET /api/coupons/SUMMER10/redemptions` | — | `200` | JSON array, 2 items |
| 7a | `POST /api/coupons/SUMMER10/redemptions` (3rd time) | `{"redeemedBy":"customer@example.com"}` | `409` | `"message"` contains `redemption limit` |
| 7b | `POST /api/coupons` (duplicate) | `{"code":"SUMMER10","maxRedemptions":2}` | `409` | `"message"` contains `already exists` |
| 7c | `GET /api/coupons/DOES-NOT-EXIST` | — | `404` | `"message"` contains `No coupon found` |
| 7d/7g | `POST /api/coupons/WINTER5/redemptions` | `{"redeemedBy":"not-an-email"}` (or any variant in 7g) | `400` | `"message"` contains `not a valid redeemer` |
| 7e | `POST /api/coupons` | `{"code":"","maxRedemptions":5}` | `400` | `"message"` contains `must not be blank` |
| 7f | `POST /api/coupons` | `{"code":"NEGATIVE1","maxRedemptions":-1}` | `400` | `"message"` contains `must not be negative` |

Remember 7d/7g need a coupon that exists and still has redemptions left — create `WINTER5` (`{"code":"WINTER5","maxRedemptions":5}`, `201`) first if you haven't already.

## 9. Interpreting the response

- **2xx** (`200`, `201`) — the request succeeded; the body is the DTO described in the tables above (`CouponResponse`, `RedemptionResponse`, or a JSON array of one of them).
- **4xx** (`400`, `404`, `409`) — the request itself was well-formed HTTP, but something about its content was rejected; the body is always an `ErrorResponse` (see §7). `status` in the body always matches the actual HTTP status code of the response.
- **5xx** — would mean something in the application crashed unexpectedly (a bug, or a lost database connection) rather than a handled failure case; nothing in the scenarios above should ever produce one. If you see a `500`, that's worth investigating as a real problem, not an expected result.

Swagger UI shows the status code prominently right above the response body for every call, so there's no need to inspect raw headers to tell these apart.
