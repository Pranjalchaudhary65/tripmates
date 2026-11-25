package com.tripmate.trust.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.trust.dto.TrustMapper;
import com.tripmate.trust.dto.TrustScoreResponse;
import com.tripmate.trust.service.TrustScoreService;
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
import org.springframework.web.bind.annotation.RestController;

/** The caller's own trust score. Nobody else's is ever returned. */
@RestController
@Tag(name = "Trust score", description = "Your trust score and how it was reached")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = TrustScoreController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = TrustScoreController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class TrustScoreController {

    static final String PROBLEM_JSON = "application/problem+json";

    private final TrustScoreService scores;

    private final TrustMapper mapper;

    public TrustScoreController(TrustScoreService scores, TrustMapper mapper) {
        this.scores = scores;
        this.mapper = mapper;
    }

    @GetMapping("/users/me/trust-score")
    @Operation(summary = "My trust score", description = "The score other travellers' filters see, with each "
            + "component's value, weight and points, and every deduction as it stands today")
    @ApiResponse(responseCode = "200", description = "The score, or pending before the first computation")
    public TrustScoreResponse mine(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(scores.mine(SessionPrincipal.of(token)));
    }
}
