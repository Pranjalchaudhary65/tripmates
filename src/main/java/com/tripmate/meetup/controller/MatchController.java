package com.tripmate.meetup.controller;

import com.tripmate.identity.service.VerificationProblem;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.identity.upload.ImageSanitizer;
import com.tripmate.meetup.dto.CancelMeetupRequest;
import com.tripmate.meetup.dto.CheckInResponse;
import com.tripmate.meetup.dto.ComfortRatingRequest;
import com.tripmate.meetup.dto.ConfirmMeetupRequest;
import com.tripmate.meetup.dto.MatchResponse;
import com.tripmate.meetup.dto.MeetupMapper;
import com.tripmate.meetup.dto.ProposeMeetupRequest;
import com.tripmate.meetup.dto.VenueOptionsResponse;
import com.tripmate.meetup.service.MeetupProperties;
import com.tripmate.meetup.service.MeetupProblem;
import com.tripmate.meetup.service.MeetupService;
import com.tripmate.platform.error.ApiProblemException;
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
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * The trust ladder between acceptance and trip lock, for one match: the video
 * call, the public meet-up, the check-in and the private comfort rating. A
 * match is addressed by the accepted join request's ID. Only its host and its
 * companion reach it; anyone else gets the 404 an unknown ID gets.
 */
@RestController
@RequestMapping(MatchController.BASE)
@Tag(name = "Meet-up", description = "The public meet-up before a trip locks")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = MatchController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "not_found: no such match, or not one the caller is in",
        content = @Content(mediaType = MatchController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = MatchController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class MatchController {

    public static final String BASE = "/matches/{matchId}";

    static final String PROBLEM_JSON = "application/problem+json";

    private final MeetupService meetups;

    private final MeetupMapper mapper;

    public MatchController(MeetupService meetups, MeetupMapper mapper) {
        this.meetups = meetups;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Where the match stands", description = "The stage, the video call, the latest meet-up "
            + "and what the caller can do next")
    @ApiResponse(responseCode = "200", description = "The match")
    public MatchResponse match(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                               @PathVariable UUID matchId) {
        return mapper.toResponse(meetups.view(SessionPrincipal.of(token), matchId));
    }

    @GetMapping("/venues")
    @Operation(summary = "Verified venues to meet at", description = "Near the given point, or the trip's "
            + "destination when none is given, each with its daylight windows for the coming days")
    @ApiResponse(responseCode = "200", description = "Venues, nearest first")
    @ApiResponse(responseCode = "400", description = "invalid_location: a point or radius out of range",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VenueOptionsResponse venues(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID matchId,
                                       @RequestParam(required = false) Double latitude,
                                       @RequestParam(required = false) Double longitude,
                                       @Parameter(description = "Up to 100") @RequestParam(required = false)
                                       Double radiusKm) {
        if ((latitude == null) != (longitude == null)
                || (latitude != null && (!Double.isFinite(latitude) || Math.abs(latitude) > 90))
                || (longitude != null && (!Double.isFinite(longitude) || Math.abs(longitude) > 180))
                || (radiusKm != null && (!Double.isFinite(radiusKm) || radiusKm <= 0
                || radiusKm > MeetupProperties.MAX_VENUE_RADIUS_KM))) {
            throw new ApiProblemException(MeetupProblem.INVALID_LOCATION);
        }
        return mapper.toResponse(meetups.venues(SessionPrincipal.of(token), matchId, latitude, longitude, radiusKm));
    }

    @PostMapping("/video-call")
    @Operation(summary = "Confirm the video call happened", description = "Both people confirm; the meet-up can be "
            + "arranged once both have")
    @ApiResponse(responseCode = "200", description = "The match")
    @ApiResponse(responseCode = "409", description = "match_closed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public MatchResponse confirmVideoCall(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                          @PathVariable UUID matchId) {
        return mapper.toResponse(meetups.confirmVideoCall(SessionPrincipal.of(token), matchId));
    }

    @PostMapping("/meetup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Propose a meet-up", description = "At an allowlisted venue, inside its daylight window, "
            + "at least 3 hours ahead and before the trip starts")
    @ApiResponse(responseCode = "201", description = "The match, with the proposal")
    @ApiResponse(responseCode = "400", description = "venue_unavailable, meetup_not_in_daylight or "
            + "meetup_time_out_of_range", content = @Content(mediaType = PROBLEM_JSON,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "match_closed, video_call_needed, emergency_contact_needed or "
            + "meetup_exists", content = @Content(mediaType = PROBLEM_JSON,
            schema = @Schema(implementation = ProblemDetail.class)))
    public MatchResponse propose(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                 @PathVariable UUID matchId, @Valid @RequestBody ProposeMeetupRequest request) {
        return mapper.toResponse(meetups.propose(SessionPrincipal.of(token), matchId, request.venueId(),
                request.scheduledAt(), request.bringingPlusOne()));
    }

    @PostMapping("/meetup/confirm")
    @Operation(summary = "Confirm the other person's proposal")
    @ApiResponse(responseCode = "200", description = "The match")
    @ApiResponse(responseCode = "409", description = "no_meetup, own_proposal, emergency_contact_needed or "
            + "meetup_passed", content = @Content(mediaType = PROBLEM_JSON,
            schema = @Schema(implementation = ProblemDetail.class)))
    public MatchResponse confirm(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                 @PathVariable UUID matchId, @Valid @RequestBody ConfirmMeetupRequest request) {
        return mapper.toResponse(meetups.confirm(SessionPrincipal.of(token), matchId, request.bringingPlusOne()));
    }

    @PostMapping("/meetup/cancel")
    @Operation(summary = "Call the meet-up off", description = "SAFETY ends the match for both people, silently and "
            + "at no cost to the caller. CONVENIENCE allows a new proposal.")
    @ApiResponse(responseCode = "200", description = "The match")
    @ApiResponse(responseCode = "409", description = "no_meetup or match_closed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public MatchResponse cancel(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                @PathVariable UUID matchId, @Valid @RequestBody CancelMeetupRequest request) {
        return mapper.toResponse(meetups.cancel(SessionPrincipal.of(token), matchId, request.type()));
    }

    @PostMapping(value = "/meetup/check-in", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Check in at the venue", description = "The phone's location must be within 100 m of the "
            + "venue, and a selfie taken now goes with it. Both, or nothing is stored.")
    @ApiResponse(responseCode = "200", description = "Checked in")
    @ApiResponse(responseCode = "400", description = "invalid_location or invalid_image",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "not_at_venue, location_too_rough, check_in_closed, "
            + "already_checked_in or no_meetup", content = @Content(mediaType = PROBLEM_JSON,
            schema = @Schema(implementation = ProblemDetail.class)))
    public CheckInResponse checkIn(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                   @PathVariable UUID matchId,
                                   @RequestParam double latitude, @RequestParam double longitude,
                                   @RequestParam(required = false) Double accuracyMeters,
                                   @RequestPart("selfie") MultipartFile selfie) throws IOException {
        if (selfie.isEmpty() || selfie.getSize() > ImageSanitizer.MAX_BYTES) {
            throw new ApiProblemException(VerificationProblem.INVALID_IMAGE);
        }
        return mapper.toResponse(meetups.checkIn(SessionPrincipal.of(token), matchId, latitude, longitude,
                accuracyMeters, selfie.getBytes()));
    }

    @PostMapping("/meetup/safe")
    @Operation(summary = "Tell TripMates you are safe", description = "Stops the reminders and the emergency "
            + "contact alert for this meet-up. It does not count as arriving.")
    @ApiResponse(responseCode = "200", description = "The match")
    public MatchResponse markSafe(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                  @PathVariable UUID matchId) {
        return mapper.toResponse(meetups.markSafe(SessionPrincipal.of(token), matchId));
    }

    @PostMapping("/meetup/feedback")
    @Operation(summary = "Say privately how the meet-up felt", description = "Never shown to the other person and "
            + "never part of any score. A rating below 3 quietly stops the match.")
    @ApiResponse(responseCode = "200", description = "The match")
    @ApiResponse(responseCode = "409", description = "feedback_not_open, feedback_given or match_closed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public MatchResponse rate(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                              @PathVariable UUID matchId, @Valid @RequestBody ComfortRatingRequest request) {
        return mapper.toResponse(meetups.rate(SessionPrincipal.of(token), matchId, request.comfortRating(),
                request.notes()));
    }
}
