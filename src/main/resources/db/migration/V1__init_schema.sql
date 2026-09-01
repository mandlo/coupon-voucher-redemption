CREATE TABLE coupons (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(50)  NOT NULL,
    max_redemptions   INTEGER      NOT NULL,
    redemption_count  INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT uq_coupons_code UNIQUE (code),
    CONSTRAINT chk_coupons_max_redemptions_non_negative CHECK (max_redemptions >= 0),
    CONSTRAINT chk_coupons_redemption_count_non_negative CHECK (redemption_count >= 0),
    -- The database-level mirror of Coupon.redeem()'s in-memory boundary check:
    -- the running count can never be persisted past the limit, no matter what
    -- application code does or doesn't check first.
    CONSTRAINT chk_coupons_redemption_count_within_limit CHECK (redemption_count <= max_redemptions)
);

CREATE TABLE redemptions (
    id           BIGSERIAL PRIMARY KEY,
    coupon_id    BIGINT       NOT NULL REFERENCES coupons (id),
    redeemed_by  VARCHAR(150) NOT NULL,
    redeemed_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_redemptions_coupon_id ON redemptions (coupon_id);
