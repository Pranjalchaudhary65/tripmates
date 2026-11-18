package com.tripmate.identity.controller;

import com.tripmate.identity.dto.CurrentUserMapper;
import com.tripmate.identity.dto.CurrentUserResponse;
import com.tripmate.identity.service.AccountService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own account. There is no {id} in the path on purpose: the
 * only account a caller can read here is the one their token names, so there
 * is no other user's ID to try.
 */
@RestController
@RequestMapping(CurrentUserController.BASE)
@Tag(name = "Account", description = "The signed-in user's own account")
public class CurrentUserController {

    public static final String BASE = "/users";

    public static final String ME = "/me";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final AccountService accounts;

    private final CurrentUserMapper mapper;

    public CurrentUserController(AccountService accounts, CurrentUserMapper mapper) {
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @GetMapping(ME)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Get the signed-in user's account")
    @ApiResponse(responseCode = "200", description = "The caller's account")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Sessions cannot be checked right now",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public CurrentUserResponse me(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(accounts.currentUser(SessionPrincipal.of(token)));
    }
}
