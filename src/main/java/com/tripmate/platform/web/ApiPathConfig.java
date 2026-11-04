package com.tripmate.platform.web;

import com.tripmate.TripmateApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Prefixes every {@code @RestController} in this codebase with
 * {@link ApiPaths#V1}. Scoped to our own base package on purpose: springdoc
 * ships its own {@code @RestController}s, and an unscoped predicate would
 * move the OpenAPI document to /api/v1/v3/api-docs. Actuator endpoints are
 * not {@code @RestController}s and stay under /actuator either way.
 */
@Configuration(proxyBeanMethods = false)
public class ApiPathConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(ApiPaths.V1,
                HandlerTypePredicate.forBasePackageClass(TripmateApplication.class)
                        .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }
}
