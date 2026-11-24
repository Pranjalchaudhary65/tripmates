package com.tripmate.trips.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.web.PageResponse;
import com.tripmate.platform.web.Paging;
import com.tripmate.trips.dto.IncomingRequestResponse;
import com.tripmate.trips.dto.JoinDecisionResponse;
import com.tripmate.trips.dto.OutgoingRequestResponse;
import com.tripmate.trips.dto.TripMapper;
import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.service.JoinRequestService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The two sides of join requests: the host's inbox with its accept and
 * reject, and the caller's own requests. Lists act on the caller's account
 * and take no user ID; a decision names the request alone, and anyone but
 * its trip's host gets 404 for it.
 */
@RestController
@RequestMapping(JoinRequestController.BASE)
@Tag(name = "Join requests", description = "Asking to join trips, and answering those asks")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = JoinRequestController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = JoinRequestController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Sessions or limits cannot be checked right now",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = JoinRequestController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class JoinRequestController {

    public static final String BASE = "/join-requests";

    public static final String INCOMING = "/incoming";

    public static final String OUTGOING = "/outgoing";

    public static final String ACCEPT = "/{requestId}/accept";

    public static final String REJECT = "/{requestId}/reject";

    static final String PROBLEM_JSON = "application/problem+json";

    private final JoinRequestService joinRequests;

    private final TripMapper mapper;

    public JoinRequestController(JoinRequestService joinRequests, TripMapper mapper) {
        this.joinRequests = joinRequests;
        this.mapper = mapper;
    }

    @GetMapping(INCOMING)
    @Operation(summary = "Requests on the caller's trips", description = "Newest first. tripId narrows it to one "
            + "of the caller's trips; any other trip ID gives an empty page.")
    @ApiResponse(responseCode = "200", description = "One page of requests")
    @ApiResponse(responseCode = "400", description = "A tripId or status that is not valid",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<IncomingRequestResponse> incoming(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_SIZE) int size) {
        return PageResponse.of(joinRequests.incoming(SessionPrincipal.of(token), tripId, status,
                Paging.of(page, size)), mapper::toResponse);
    }

    @GetMapping(OUTGOING)
    @Operation(summary = "The caller's own requests", description = "Newest first")
    @ApiResponse(responseCode = "200", description = "One page of requests")
    @ApiResponse(responseCode = "400", description = "A status that is not valid",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<OutgoingRequestResponse> outgoing(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_SIZE) int size) {
        return PageResponse.of(joinRequests.outgoing(SessionPrincipal.of(token), status, Paging.of(page, size)),
                mapper::toResponse);
    }

    @PostMapping(ACCEPT)
    @Operation(summary = "Accept a request on one of the caller's trips", description = "Takes one of the trip's "
            + "places. When it is the last one the trip becomes FULL.")
    @ApiResponse(responseCode = "200", description = "The accepted request and the trip's places")
    @ApiResponse(responseCode = "404", description = "not_found: no such request, or not on the caller's trip",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "join_request_decided, trip_full, trip_closed or trip_busy",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public JoinDecisionResponse accept(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID requestId) {
        return mapper.toResponse(joinRequests.accept(SessionPrincipal.of(token), requestId));
    }

    @PostMapping(REJECT)
    @Operation(summary = "Reject a request on one of the caller's trips", description = "Final: the same person "
            + "cannot ask to join that trip again.")
    @ApiResponse(responseCode = "200", description = "The rejected request")
    @ApiResponse(responseCode = "404", description = "not_found: no such request, or not on the caller's trip",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "join_request_decided or trip_busy",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public JoinDecisionResponse reject(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID requestId) {
        return mapper.toResponse(joinRequests.reject(SessionPrincipal.of(token), requestId));
    }
}
