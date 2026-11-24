package com.tripmate.trips.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.web.PageResponse;
import com.tripmate.platform.web.Paging;
import com.tripmate.trips.dto.CreateTripRequest;
import com.tripmate.trips.dto.JoinRequestCreatedResponse;
import com.tripmate.trips.dto.TripDetailResponse;
import com.tripmate.trips.dto.TripMapper;
import com.tripmate.trips.dto.TripSummaryResponse;
import com.tripmate.trips.repository.TripFilter;
import com.tripmate.trips.service.JoinRequestService;
import com.tripmate.trips.service.TripProblem;
import com.tripmate.trips.service.TripService;
import com.tripmate.vibe.entity.BudgetBand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Trips: open one, browse other people's, read one, ask to join one. No
 * route takes a user ID; the host of a new trip and the sender of a join
 * request are always the account the access token names.
 */
@RestController
@RequestMapping(TripController.BASE)
@Tag(name = "Trips", description = "Opening, browsing and joining trips")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = TripController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = TripController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Sessions or limits cannot be checked right now",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = TripController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class TripController {

    public static final String BASE = "/trips";

    public static final String HOSTED = "/hosted";

    public static final String TRIP = "/{tripId}";

    public static final String JOIN_REQUESTS = "/{tripId}/join-requests";

    /** The widest circle a browse may search, so one query cannot scan the whole map. */
    public static final double MAX_RADIUS_KM = 1000;

    static final String PROBLEM_JSON = "application/problem+json";

    private final TripService trips;

    private final JoinRequestService joinRequests;

    private final TripMapper mapper;

    public TripController(TripService trips, JoinRequestService joinRequests, TripMapper mapper) {
        this.trips = trips;
        this.joinRequests = joinRequests;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a trip, hosted by the caller")
    @ApiResponse(responseCode = "201", description = "The new trip")
    @ApiResponse(responseCode = "400", description = "invalid_trip_dates, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "too_many_trips",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public TripDetailResponse open(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                   @Valid @RequestBody CreateTripRequest request) {
        SessionPrincipal caller = SessionPrincipal.of(token);
        trips.requireValidDates(request.startDate(), request.endDate());
        return mapper.toResponse(trips.open(caller, request.toDetails()));
    }

    @GetMapping
    @Operation(summary = "Browse open trips other people host",
            description = "Trips not started yet, soonest first. A search point needs latitude, longitude and "
                    + "radiusKm together. from and to keep trips that start on or after from and end on or "
                    + "before to. Trips a deal-breaker on either side rules out are left out; each trip carries "
                    + "the caller's match score where both sides have a vibe profile.")
    @ApiResponse(responseCode = "200", description = "One page of trips")
    @ApiResponse(responseCode = "400", description = "invalid_location_filter, invalid_date_filter, or a value "
            + "that is not a number, date or budget band",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<TripSummaryResponse> browse(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @Parameter(description = "Up to 1000") @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Repeat for several bands") @RequestParam(required = false)
            List<BudgetBand> budget,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_SIZE) int size) {
        TripFilter filter = new TripFilter(near(latitude, longitude, radiusKm), from, dateTo(from, to),
                budget == null || budget.isEmpty() ? Set.of() : EnumSet.copyOf(budget));
        TripService.Browse found = trips.browse(SessionPrincipal.of(token), filter, Paging.of(page, size));
        return PageResponse.of(found.page(), card -> mapper.toResponse(card, found.scores().get(card.id())));
    }

    @GetMapping(HOSTED)
    @Operation(summary = "The trips the caller hosts", description = "Upcoming first, each with its waiting requests")
    @ApiResponse(responseCode = "200", description = "One page of trips")
    public PageResponse<TripSummaryResponse> hosted(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "" + Paging.DEFAULT_SIZE)
                                                    int size) {
        return PageResponse.of(trips.hosted(SessionPrincipal.of(token), Paging.of(page, size)), mapper::toResponse);
    }

    @GetMapping(TRIP)
    @Operation(summary = "One trip",
            description = "Open and full trips are visible to everyone signed in; any other trip only to its "
                    + "host and to people with a request on it. Anyone but the host also gets how well they match "
                    + "it, with the reasons, or the deal-breaker that rules it out.")
    @ApiResponse(responseCode = "200", description = "The trip")
    @ApiResponse(responseCode = "404", description = "not_found: no such trip, or not one the caller can see",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public TripDetailResponse detail(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                     @PathVariable UUID tripId) {
        return mapper.toResponse(trips.detail(SessionPrincipal.of(token), tripId));
    }

    @PostMapping(JOIN_REQUESTS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask to join a trip", description = "The host accepts or rejects it. One request per trip.")
    @ApiResponse(responseCode = "201", description = "The request, waiting for the host")
    @ApiResponse(responseCode = "404", description = "not_found: no such trip, or not one the caller can see",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "own_trip, join_request_exists, trip_full, trip_closed or "
            + "too_many_pending_requests, or deal_breaker_conflict when a deal-breaker on either side rules the trip "
            + "out",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public JoinRequestCreatedResponse requestToJoin(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                    @PathVariable UUID tripId) {
        return mapper.toCreatedResponse(joinRequests.request(SessionPrincipal.of(token), tripId));
    }

    private static TripFilter.Near near(Double latitude, Double longitude, Double radiusKm) {
        if (latitude == null && longitude == null && radiusKm == null) {
            return null;
        }
        if (latitude == null || longitude == null || radiusKm == null || !Double.isFinite(latitude)
                || !Double.isFinite(longitude) || !Double.isFinite(radiusKm) || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180 || radiusKm <= 0 || radiusKm > MAX_RADIUS_KM) {
            throw new ApiProblemException(TripProblem.INVALID_LOCATION_FILTER);
        }
        return new TripFilter.Near(latitude, longitude, radiusKm);
    }

    private static LocalDate dateTo(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiProblemException(TripProblem.INVALID_DATE_FILTER);
        }
        return to;
    }
}
