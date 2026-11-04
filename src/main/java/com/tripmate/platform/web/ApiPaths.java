package com.tripmate.platform.web;

/**
 * Versioned base path shared by every application endpoint. Controllers
 * never repeat it in their own {@code @RequestMapping} - {@link ApiPathConfig}
 * prepends it - so moving to a v2 is one constant, not a find-and-replace.
 */
public final class ApiPaths {

    public static final String V1 = "/api/v1";

    private ApiPaths() {
    }
}
