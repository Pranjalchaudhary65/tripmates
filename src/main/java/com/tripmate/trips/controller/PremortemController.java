package com.tripmate.trips.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.trips.dto.PremortemMapper;
import com.tripmate.trips.dto.PremortemResponse;
import com.tripmate.trips.service.PremortemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The conflict pre-mortem: what a trip's travellers should talk through before they go. */
@RestController
@Tag(name = "Trips", description = "Trips and joining them")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PremortemController {

    private final PremortemService premortems;

    private final PremortemMapper mapper;

    public PremortemController(PremortemService premortems, PremortemMapper mapper) {
        this.premortems = premortems;
        this.mapper = mapper;
    }

    @GetMapping("/trips/{tripId}/premortem")
    @Operation(summary = "Conversations to have before the trip", description = "For the caller and each other "
            + "traveller, the biggest gaps between their vibes, each with a question to settle. Only the host and "
            + "companions still on the trip.")
    @ApiResponse(responseCode = "200", description = "The pre-mortem")
    @ApiResponse(responseCode = "404", description = "not_found: no such trip, or not one the caller is on",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PremortemResponse premortem(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID tripId) {
        return mapper.toResponse(premortems.forTrip(SessionPrincipal.of(token), tripId));
    }
}
