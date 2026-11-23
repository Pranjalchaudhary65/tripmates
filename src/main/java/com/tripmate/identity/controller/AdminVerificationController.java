package com.tripmate.identity.controller;

import com.tripmate.identity.dto.ReviewDecisionRequest;
import com.tripmate.identity.dto.ReviewItemResponse;
import com.tripmate.identity.dto.VerificationMapper;
import com.tripmate.identity.service.AdminVerificationService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Selfies the liveness check flagged for a person to decide. Closed to all
 * but an ADMIN signed in with a second factor, by SecurityConfig, before this
 * is reached.
 */
@RestController
@RequestMapping(AdminVerificationController.BASE)
@Tag(name = "Admin", description = "Moderation. Requires an ADMIN session signed in with a second factor.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = AdminVerificationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Not an ADMIN, or this session did not pass a second factor",
        content = @Content(mediaType = AdminVerificationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class AdminVerificationController {

    public static final String BASE = AdminUserController.ADMIN_BASE + "/verifications";

    public static final String DECISION = "/{verificationId}/decision";

    static final String PROBLEM_JSON = "application/problem+json";

    private final AdminVerificationService reviews;

    private final VerificationMapper mapper;

    public AdminVerificationController(AdminVerificationService reviews, VerificationMapper mapper) {
        this.reviews = reviews;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Selfies waiting for review, oldest first")
    @ApiResponse(responseCode = "200", description = "One page of the queue")
    public PageResponse<ReviewItemResponse> queue(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(reviews.queue(SessionPrincipal.of(token), page, size), mapper::toResponse);
    }

    @PostMapping(DECISION)
    @Operation(summary = "Approve or reject a flagged selfie",
            description = "Approving raises the account to SELFIE_MATCH. Nobody can decide their own.")
    @ApiResponse(responseCode = "200", description = "The decided verification")
    @ApiResponse(responseCode = "400", description = "Malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not_found",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "verification_already_decided",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ReviewItemResponse decide(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                     @PathVariable UUID verificationId,
                                     @Valid @RequestBody ReviewDecisionRequest request) {
        boolean approve = request.decision() == ReviewDecisionRequest.Decision.APPROVE;
        return mapper.toResponse(reviews.decide(SessionPrincipal.of(token), verificationId, approve));
    }
}
