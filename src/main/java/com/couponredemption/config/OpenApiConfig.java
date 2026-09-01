package com.couponredemption.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI couponRedemptionOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Coupon/Voucher Redemption API")
                        .description("API for issuing and redeeming coupons with a bounded number of uses")
                        .version("v0.0.1"));
    }
}
