package com.tripmate.compliance.controller;

import com.tripmate.compliance.service.ComplianceProblem;
import com.tripmate.compliance.service.DataRequestService;
import com.tripmate.compliance.service.DataRequestService.DataRequest;
import com.tripmate.compliance.service.DataRequestService.RequestType;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.openapi.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** The caller's own data requests: a copy of their data, or deleting their account. */
@RestController
@Tag(name = "Your data", description = "Download your data or delete your account")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class DataRequestController {

    private final DataRequestService requests;

    public DataRequestController(DataRequestService requests) {
        this.requests = requests;
    }

    @GetMapping("/users/me/data-requests")
    @Operation(summary = "My data requests", description = "Newest first")
    public RequestList mine(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return new RequestList(requests.mine(SessionPrincipal.of(token)).stream().map(Item::of).toList());
    }

    @PostMapping("/users/me/data-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask for a copy of my data, or to delete my account", description = "DELETION needs the "
            + "account password. Both are done within a few minutes; a deletion cannot be undone.")
    @ApiResponse(responseCode = "201", description = "Recorded")
    @ApiResponse(responseCode = "400", description = "invalid_request_type or reauthentication_failed",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "data_request_pending",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public Item request(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                        @RequestBody NewRequest request) {
        RequestType type = Arrays.stream(RequestType.values()).filter(value -> value.name().equals(request.type()))
                .findFirst().orElseThrow(() -> new ApiProblemException(ComplianceProblem.INVALID_REQUEST_TYPE));
        return Item.of(requests.request(SessionPrincipal.of(token), type, request.password()));
    }

    @GetMapping("/users/me/data-requests/{requestId}/export")
    @Operation(summary = "Download my data", description = "A JSON file, while the export has not expired")
    @ApiResponse(responseCode = "200", description = "The export")
    @ApiResponse(responseCode = "404", description = "not_found: no such request of yours",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "export_not_ready",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<byte[]> download(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                           @PathVariable UUID requestId) {
        byte[] json = requests.download(SessionPrincipal.of(token), requestId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("tripmates-data.json").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(json);
    }

    /** @param password required for DELETION, ignored for EXPORT */
    public record NewRequest(String type, String password) {
    }

    public record RequestList(List<Item> items) {
    }

    public record Item(UUID id, String type, String status, Instant requestedAt, Instant completedAt,
                       Instant expiresAt, boolean downloadable) {

        static Item of(DataRequest request) {
            return new Item(request.id(), request.type().name(), request.status().name(), request.requestedAt(),
                    request.completedAt(), request.expiresAt(), request.downloadable());
        }
    }
}