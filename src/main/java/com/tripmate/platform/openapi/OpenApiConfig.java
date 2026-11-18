package com.tripmate.platform.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document metadata and the bearer security scheme. Which paths are
 * documented, and where the document and UI are served, is fixed in
 * application.yml under springdoc.* so SecurityConfig's permit list and the
 * served paths cannot drift apart through a library default changing.
 *
 * <p>Protected operations name {@link #BEARER_AUTH} with
 * {@code @SecurityRequirement}; that is what makes Swagger UI's Authorize
 * button attach the token to them, and leaves the anonymous routes alone.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI tripmateOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TripMates API")
                        .version("v1")
                        .description("Trust and accountability layer for travelling with strangers. "
                                + "Errors are RFC 9457 application/problem+json bodies carrying a correlationId. "
                                + "To call a protected route here, log in, copy session.accessToken from the "
                                + "response and paste it into Authorize."))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("The access token from login or refresh, valid for 15 minutes")));
    }
}
