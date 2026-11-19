package com.tripmate.identity.controller;

import com.tripmate.identity.dto.AdminUserMapper;
import com.tripmate.identity.dto.AdminUserResponse;
import com.tripmate.identity.service.AdminUserService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Moderation views of accounts. Everything under /admin is closed to anyone
 * but an ADMIN whose session passed a second factor, by SecurityConfig, before
 * a controller is reached; the service checks the database again.
 */
@RestController
@RequestMapping(AdminUserController.BASE)
@Tag(name = "Admin", description = "Moderation. Requires an ADMIN session signed in with a second factor.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminUserController {

    public static final String ADMIN_BASE = "/admin";

    public static final String BASE = ADMIN_BASE + "/users";

    public static final String BY_ID = "/{userId}";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final AdminUserService adminUsers;

    private final AdminUserMapper mapper;

    public AdminUserController(AdminUserService adminUsers, AdminUserMapper mapper) {
        this.adminUsers = adminUsers;
        this.mapper = mapper;
    }

    @GetMapping(BY_ID)
    @Operation(summary = "Look up an account for moderation")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "400", description = "userId is not a UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Not an ADMIN, or this session did not pass a second factor",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not_found",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Sessions cannot be checked right now",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public AdminUserResponse find(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                  @PathVariable UUID userId) {
        return mapper.toResponse(adminUsers.find(SessionPrincipal.of(token), userId));
    }
}
