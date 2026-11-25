package com.tripmate.moderation.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.moderation.dto.ModerationDtos.BlockListResponse;
import com.tripmate.moderation.dto.ModerationDtos.BlockRequest;
import com.tripmate.moderation.dto.ModerationDtos.BlockResponse;
import com.tripmate.moderation.dto.ModerationDtos.ReportRequest;
import com.tripmate.moderation.dto.ModerationDtos.ReportResponse;
import com.tripmate.moderation.dto.ModerationMapper;
import com.tripmate.moderation.service.ModerationProblem;
import com.tripmate.moderation.service.ModerationService;
import com.tripmate.moderation.service.ModerationService.Target;
import com.tripmate.moderation.service.ReportReason;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.UUID;

/**
 * Block and report someone the caller was matched or travelled with. Anyone
 * else, a stranger's ID included, is the 404 an unknown match gets.
 */
@RestController
@Tag(name = "Report and block", description = "Blocking and reporting people you met through a trip")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = ModerationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = ModerationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class ModerationController {

    static final String PROBLEM_JSON = "application/problem+json";

    private final ModerationService moderation;

    private final ModerationMapper mapper;

    public ModerationController(ModerationService moderation, ModerationMapper mapper) {
        this.moderation = moderation;
        this.mapper = mapper;
    }

    @PostMapping("/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Block someone", description = "Hides each of you from the other: trips, join requests and "
            + "messages. Blocking someone already blocked returns the existing block.")
    @ApiResponse(responseCode = "201", description = "The block")
    @ApiResponse(responseCode = "400", description = "invalid_target",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not_found: not someone you were matched or travelled with",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public BlockResponse block(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                               @RequestBody BlockRequest request) {
        return mapper.toResponse(moderation.block(SessionPrincipal.of(token),
                Target.of(request.matchId(), request.tripId(), request.userId())));
    }

    @GetMapping("/blocks")
    @Operation(summary = "People I have blocked")
    @ApiResponse(responseCode = "200", description = "Newest first")
    public BlockListResponse blocked(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toListResponse(moderation.blocked(SessionPrincipal.of(token)));
    }

    @DeleteMapping("/blocks/{blockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Lift a block")
    @ApiResponse(responseCode = "204", description = "Lifted")
    @ApiResponse(responseCode = "404", description = "not_found: no such block of yours",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public void unblock(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token, @PathVariable UUID blockId) {
        moderation.unblock(SessionPrincipal.of(token), blockId);
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report someone", description = "A moderator reviews it. The details are encrypted. The "
            + "person is not told who reported them, and you are not told what happened.")
    @ApiResponse(responseCode = "201", description = "Received")
    @ApiResponse(responseCode = "400", description = "invalid_target, invalid_reason or invalid_details",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not_found: not someone you were matched or travelled with",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "already_reported",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ReportResponse report(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                 @RequestBody ReportRequest request) {
        Target target = Target.of(request.matchId(), request.tripId(), request.userId());
        return mapper.toResponse(moderation.report(SessionPrincipal.of(token), target, reason(request.reason()),
                request.details()));
    }

    private static ReportReason reason(String raw) {
        return Arrays.stream(ReportReason.values()).filter(value -> value.name().equals(raw)).findFirst()
                .orElseThrow(() -> new ApiProblemException(ModerationProblem.INVALID_REASON));
    }
}
