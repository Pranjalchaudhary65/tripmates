package com.tripmate.identity.controller;

import com.tripmate.identity.dto.UpiIdMapper;
import com.tripmate.identity.dto.UpiIdRequest;
import com.tripmate.identity.dto.UpiIdResponse;
import com.tripmate.identity.service.UpiIdService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own UPI ID. No user ID anywhere: there is nobody else's to reach. */
@RestController
@RequestMapping(CurrentUserController.BASE + UpiIdController.PATH)
@Tag(name = "Account", description = "The signed-in user's own account")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class UpiIdController {

    public static final String PATH = "/me/upi-id";

    private final UpiIdService upiIds;

    private final UpiIdMapper mapper;

    public UpiIdController(UpiIdService upiIds, UpiIdMapper mapper) {
        this.upiIds = upiIds;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The caller's UPI ID")
    @ApiResponse(responseCode = "200", description = "The UPI ID, or upiId: null when none is set")
    public UpiIdResponse get(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(upiIds.mine(SessionPrincipal.of(token)).orElse(null));
    }

    @PutMapping
    @Operation(summary = "Set or replace the caller's UPI ID", description = "Trip members who owe the caller money "
            + "get a payment link to it. TripMates never sends or receives the payment.")
    @ApiResponse(responseCode = "200", description = "The UPI ID as stored")
    @ApiResponse(responseCode = "400", description = "invalid_upi_id, or a malformed request",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public UpiIdResponse set(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                             @Valid @RequestBody UpiIdRequest request) {
        return mapper.toResponse(upiIds.set(SessionPrincipal.of(token), request.upiId()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the caller's UPI ID", description = "People who owe the caller are then told to "
            + "ask for it in chat")
    @ApiResponse(responseCode = "204", description = "Removed, or there was none")
    public void remove(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        upiIds.remove(SessionPrincipal.of(token));
    }
}
