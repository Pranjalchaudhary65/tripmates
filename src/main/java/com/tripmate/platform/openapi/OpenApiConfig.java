package com.tripmate.platform.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document metadata only. Which paths are documented, and where the
 * document and UI are served, is fixed in application.yml under springdoc.*
 * so SecurityConfig's permit list and the served paths cannot drift apart
 * through a library default changing.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI tripmateOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TripMates API")
                .version("v1")
                .description("Trust and accountability layer for travelling with strangers. "
                        + "Errors are RFC 9457 application/problem+json bodies carrying a correlationId."));
    }
}
