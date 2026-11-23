package com.tripmate.vibe.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.vibe.dto.DealBreakersRequest;
import com.tripmate.vibe.dto.QuizAnswersRequest;
import com.tripmate.vibe.dto.QuizResponse;
import com.tripmate.vibe.dto.VibeAdjustRequest;
import com.tripmate.vibe.dto.VibeMapper;
import com.tripmate.vibe.dto.VibeProfileResponse;
import com.tripmate.vibe.service.VibeService;
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
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own vibe quiz, profile and deal-breakers. No route takes a
 * user ID; the account is always the one the access token names.
 */
@RestController
@RequestMapping(VibeController.BASE)
@Tag(name = "Vibe profile", description = "The travel-style quiz, its result and deal-breakers")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = VibeController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Sessions or limits cannot be checked right now",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = VibeController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class VibeController {

    public static final String BASE = "/vibe";

    public static final String QUIZ = "/quiz";

    public static final String QUIZ_DRAFT = "/quiz/draft";

    public static final String QUIZ_SUBMIT = "/quiz/submit";

    public static final String PROFILE = "/profile";

    public static final String DEAL_BREAKERS = "/deal-breakers";

    static final String PROBLEM_JSON = "application/problem+json";

    private final VibeService vibes;

    private final VibeMapper mapper;

    public VibeController(VibeService vibes, VibeMapper mapper) {
        this.vibes = vibes;
        this.mapper = mapper;
    }

    @GetMapping(QUIZ)
    @Operation(summary = "The quiz questions, with any answers saved so far")
    @ApiResponse(responseCode = "200", description = "The quiz")
    public QuizResponse quiz(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(vibes.quiz(SessionPrincipal.of(token)));
    }

    @PutMapping(QUIZ_DRAFT)
    @Operation(summary = "Save the answers given so far",
            description = "Replaces the saved draft. Questions may be left out; every answer given must be an "
                    + "option of its question.")
    @ApiResponse(responseCode = "200", description = "The saved draft")
    @ApiResponse(responseCode = "400", description = "invalid_quiz_answer, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many changes for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public QuizResponse.Draft saveDraft(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                        @Valid @RequestBody QuizAnswersRequest request) {
        return mapper.toResponse(vibes.saveDraft(SessionPrincipal.of(token), request.answers()));
    }

    @PostMapping(QUIZ_SUBMIT)
    @Operation(summary = "Finish the quiz",
            description = "Needs an answer to every question. Scores the answers into the vibe profile, "
                    + "replacing any earlier one along with hand adjustments, and deletes the draft.")
    @ApiResponse(responseCode = "200", description = "The new profile")
    @ApiResponse(responseCode = "400", description = "quiz_incomplete, invalid_quiz_answer, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many changes for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VibeProfileResponse submit(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                      @Valid @RequestBody QuizAnswersRequest request) {
        return mapper.toResponse(vibes.submit(SessionPrincipal.of(token), request.answers()));
    }

    @GetMapping(PROFILE)
    @Operation(summary = "The caller's vibe profile and deal-breakers")
    @ApiResponse(responseCode = "200", description = "Either part is null until it exists")
    public VibeProfileResponse profile(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(vibes.profile(SessionPrincipal.of(token)));
    }

    @PutMapping(PROFILE)
    @Operation(summary = "Adjust the profile by hand",
            description = "Sets all eight dimensions. Needs a finished quiz.")
    @ApiResponse(responseCode = "200", description = "The adjusted profile")
    @ApiResponse(responseCode = "400", description = "Malformed request or a value out of range",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "vibe_profile_missing",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many changes for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VibeProfileResponse adjust(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                      @Valid @RequestBody VibeAdjustRequest request) {
        return mapper.toResponse(vibes.adjust(SessionPrincipal.of(token), request.toDimensions()));
    }

    @PutMapping(DEAL_BREAKERS)
    @Operation(summary = "Set the deal-breakers", description = "Hard filters, applied before any match score.")
    @ApiResponse(responseCode = "200", description = "The profile with the new deal-breakers")
    @ApiResponse(responseCode = "400", description = "Malformed request or a value out of range",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many changes for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VibeProfileResponse replaceDealBreakers(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                   @Valid @RequestBody DealBreakersRequest request) {
        return mapper.toResponse(vibes.replaceDealBreakers(SessionPrincipal.of(token), request.smoking(),
                request.drinking(), request.genderPreference(), request.minTrustScore()));
    }
}
