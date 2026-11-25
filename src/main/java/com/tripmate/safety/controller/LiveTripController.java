package com.tripmate.safety.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.safety.dto.LiveTripResponse;
import com.tripmate.safety.dto.LocationRequest;
import com.tripmate.safety.dto.SafetyMapper;
import com.tripmate.safety.dto.SharingRequest;
import com.tripmate.safety.dto.SosRequest;
import com.tripmate.safety.service.LiveTripService;
import com.tripmate.safety.service.LiveTripViews.ReportedLocation;
import com.tripmate.safety.service.SafetyProblem;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Live trip mode: sharing the trip with the caller's emergency contact,
 * "are you safe?" check-ins, and SOS. Open to the host and companions still
 * on the trip; anyone else gets the 404 an unknown trip gets. Every safety
 * action is free: none of them affects anyone's trust score.
 */
@RestController
@Tag(name = "Live trip", description = "Check-ins and SOS while travelling")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = LiveTripController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "not_found: no such trip, or not one the caller is on",
        content = @Content(mediaType = LiveTripController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = LiveTripController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class LiveTripController {

    static final String PROBLEM_JSON = "application/problem+json";

    private static final String LIVE = "/trips/{tripId}/live";

    private final LiveTripService liveTrips;

    private final SafetyMapper mapper;

    public LiveTripController(LiveTripService liveTrips, SafetyMapper mapper) {
        this.liveTrips = liveTrips;
        this.mapper = mapper;
    }

    @GetMapping(LIVE)
    @Operation(summary = "The live trip screen", description = "Sharing, the next check-in, the other travellers "
            + "and any open SOS")
    @ApiResponse(responseCode = "200", description = "The live trip")
    public LiveTripResponse view(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                 @PathVariable UUID tripId) {
        return mapper.toResponse(liveTrips.view(SessionPrincipal.of(token), tripId));
    }

    @PutMapping(LIVE + "/sharing")
    @Operation(summary = "Share the trip with the caller's emergency contact", description = "Or change how often "
            + "check-ins come and the note the contact gets")
    @ApiResponse(responseCode = "200", description = "The live trip")
    @ApiResponse(responseCode = "400", description = "invalid_check_in_interval or invalid_note",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "trip_not_locked, trip_over or emergency_contact_needed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public LiveTripResponse share(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                  @PathVariable UUID tripId, @Valid @RequestBody SharingRequest request) {
        return mapper.toResponse(liveTrips.share(SessionPrincipal.of(token), tripId, request.checkInEveryHours(),
                request.note()));
    }

    @DeleteMapping(LIVE + "/sharing")
    @Operation(summary = "Stop sharing the trip", description = "Free and needs no reason")
    @ApiResponse(responseCode = "200", description = "The live trip")
    public LiveTripResponse stopSharing(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                        @PathVariable UUID tripId) {
        return mapper.toResponse(liveTrips.stopSharing(SessionPrincipal.of(token), tripId));
    }

    @PostMapping(LIVE + "/check-in")
    @Operation(summary = "I'm safe", description = "Answers the check-in waiting and schedules the next. The location "
            + "is optional; only a rounded point is kept in the clear.")
    @ApiResponse(responseCode = "200", description = "The live trip")
    @ApiResponse(responseCode = "400", description = "invalid_location",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "not_sharing, trip_not_locked or trip_over",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public LiveTripResponse checkIn(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                    @PathVariable UUID tripId, @RequestBody LocationRequest request) {
        return mapper.toResponse(liveTrips.checkIn(SessionPrincipal.of(token), tripId,
                location(request.latitude(), request.longitude(), request.accuracyMeters())));
    }

    @PostMapping("/trips/{tripId}/sos")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "SOS", description = "Alerts the caller's emergency contact with their last location, the "
            + "trip and who they are with, and flags it to TripMates moderators. Always free. Retrying with the same "
            + "clientSosId raises one SOS.")
    @ApiResponse(responseCode = "201", description = "The live trip, with the open SOS")
    @ApiResponse(responseCode = "400", description = "invalid_location",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public LiveTripResponse sos(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                @PathVariable UUID tripId, @Valid @RequestBody SosRequest request) {
        return mapper.toResponse(liveTrips.raiseSos(SessionPrincipal.of(token), tripId, request.clientSosId(),
                location(request.latitude(), request.longitude(), request.accuracyMeters())));
    }

    @PostMapping("/sos/{sosId}/resolve")
    @Operation(summary = "I'm safe now", description = "Closes the caller's own SOS")
    @ApiResponse(responseCode = "200", description = "The live trip")
    public LiveTripResponse resolveSos(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID sosId) {
        return mapper.toResponse(liveTrips.resolveSos(SessionPrincipal.of(token), sosId));
    }

    private static ReportedLocation location(Double latitude, Double longitude, Double accuracyMeters) {
        if (latitude == null && longitude == null) {
            return null;
        }
        if (latitude == null || longitude == null) {
            throw new ApiProblemException(SafetyProblem.INVALID_LOCATION);
        }
        return new ReportedLocation(latitude, longitude, accuracyMeters);
    }
}
