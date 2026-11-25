package com.tripmate.trust.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.trust.dto.MyReviewsResponse;
import com.tripmate.trust.dto.ReviewMapper;
import com.tripmate.trust.dto.ReviewRequest;
import com.tripmate.trust.dto.TripReviewsResponse;
import com.tripmate.trust.service.ReviewService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Double-blind reviews between people who travelled together. A trip's
 * reviews are open to the host and companions still on it when it ended;
 * anyone else gets the 404 an unknown trip gets.
 */
@RestController
@Tag(name = "Reviews", description = "Double-blind reviews after a trip")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = ReviewController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = ReviewController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class ReviewController {

    static final String PROBLEM_JSON = "application/problem+json";

    private final ReviewService reviews;

    private final ReviewMapper mapper;

    public ReviewController(ReviewService reviews, ReviewMapper mapper) {
        this.reviews = reviews;
        this.mapper = mapper;
    }

    @GetMapping("/trips/{tripId}/reviews")
    @Operation(summary = "A trip's review screen", description = "Who the caller travelled with, their own reviews, "
            + "and reviews about them once visible")
    @ApiResponse(responseCode = "200", description = "The review screen")
    @ApiResponse(responseCode = "404", description = "not_found: no such trip, or not one the caller travelled on",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public TripReviewsResponse forTrip(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID tripId) {
        return mapper.toResponse(reviews.forTrip(SessionPrincipal.of(token), tripId));
    }

    @PostMapping("/trips/{tripId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Review a fellow traveller", description = "Hidden from them until they review back or "
            + "the window closes. Cannot be changed.")
    @ApiResponse(responseCode = "201", description = "The review screen, with the new review")
    @ApiResponse(responseCode = "400", description = "invalid_rating, invalid_comment or not_a_travel_companion",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not_found: no such trip, or not one the caller travelled on",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "trip_not_reviewable, review_window_not_open, "
            + "review_window_closed or already_reviewed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public TripReviewsResponse submit(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                      @PathVariable UUID tripId, @Valid @RequestBody ReviewRequest request) {
        return mapper.toResponse(reviews.submit(SessionPrincipal.of(token), tripId, request.revieweeId(),
                request.rating(), request.comment()));
    }

    @GetMapping("/users/me/reviews")
    @Operation(summary = "My reviews", description = "Trips waiting for the caller's reviews, and visible reviews "
            + "about the caller")
    @ApiResponse(responseCode = "200", description = "The reviews inbox")
    public MyReviewsResponse mine(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(reviews.mine(SessionPrincipal.of(token)));
    }
}
