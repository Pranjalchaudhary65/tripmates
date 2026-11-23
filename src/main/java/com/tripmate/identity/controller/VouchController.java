package com.tripmate.identity.controller;

import com.tripmate.identity.dto.VerificationMapper;
import com.tripmate.identity.dto.VouchRequest;
import com.tripmate.identity.dto.VouchSummaryResponse;
import com.tripmate.identity.service.VouchService;
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
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Vouches the caller has given. A vouch is withdrawn by its own ID, and only
 * by the account that gave it; to anyone else that ID does not exist.
 */
@RestController
@RequestMapping(VouchController.BASE)
@Tag(name = "Vouching", description = "Putting your own standing behind someone you know")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = VouchController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Sessions or limits cannot be checked right now",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = VouchController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class VouchController {

    public static final String BASE = "/vouches";

    public static final String BY_ID = "/{vouchId}";

    static final String PROBLEM_JSON = "application/problem+json";

    private final VouchService vouches;

    private final VerificationMapper mapper;

    public VouchController(VouchService vouches, VerificationMapper mapper) {
        this.vouches = vouches;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The caller's active vouches and how many people vouch for them")
    @ApiResponse(responseCode = "200", description = "The caller's vouches")
    public VouchSummaryResponse summary(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(vouches.summary(SessionPrincipal.of(token)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Vouch for someone", description = "Needs SELFIE_MATCH. At most 5 active vouches at once.")
    @ApiResponse(responseCode = "201", description = "The vouch")
    @ApiResponse(responseCode = "400", description = "cannot_vouch_for_self, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not_found: no active account with that ID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "vouch_requires_selfie_match, vouch_limit_reached or "
            + "already_vouching",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many vouches from this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VouchSummaryResponse.Given give(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                           @Valid @RequestBody VouchRequest request) {
        return mapper.toResponse(vouches.give(SessionPrincipal.of(token), request.vouchedUserId()));
    }

    @DeleteMapping(BY_ID)
    @Operation(summary = "Withdraw a vouch")
    @ApiResponse(responseCode = "204", description = "Withdrawn")
    @ApiResponse(responseCode = "404", description = "not_found: no active vouch of the caller's with that ID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> revoke(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID vouchId) {
        vouches.revoke(SessionPrincipal.of(token), vouchId);
        return ResponseEntity.noContent().build();
    }
}
