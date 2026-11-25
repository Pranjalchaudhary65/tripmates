package com.tripmate.identity.controller;

import com.tripmate.identity.service.ConsentService;
import com.tripmate.identity.service.ConsentService.Consent;
import com.tripmate.identity.service.ConsentService.ConsentType;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.identity.service.IdentityProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** The caller's own consents: what they agreed to, which version, and when. */
@RestController
@Tag(name = "Consents", description = "Terms, privacy policy and optional consents")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ConsentController {

    private final ConsentService consents;

    public ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @GetMapping("/users/me/consents")
    @Operation(summary = "My consents", description = "The current state of each consent type")
    public ConsentsResponse mine(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return ConsentsResponse.of(consents.mine(SessionPrincipal.of(token)));
    }

    @PutMapping("/users/me/consents")
    @Operation(summary = "Give or withdraw an optional consent", description = "LOCATION_SHARING or MARKETING. The "
            + "terms and privacy policy can only be withdrawn by deleting the account.")
    @ApiResponse(responseCode = "200", description = "Every consent after the change")
    @ApiResponse(responseCode = "400", description = "consent_not_optional",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ConsentsResponse set(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                @RequestBody ConsentRequest request) {
        ConsentType parsed = Arrays.stream(ConsentType.values()).filter(value -> value.name().equals(request.type()))
                .findFirst().orElseThrow(() -> new ApiProblemException(IdentityProblem.CONSENT_NOT_OPTIONAL));
        if (request.granted() == null) {
            throw new ApiProblemException(IdentityProblem.CONSENT_NOT_OPTIONAL);
        }
        return ConsentsResponse.of(consents.set(SessionPrincipal.of(token), parsed, request.granted()));
    }

    public record ConsentRequest(String type, @NotNull Boolean granted) {
    }

    public record ConsentsResponse(List<Item> items) {

        static ConsentsResponse of(List<Consent> consents) {
            return new ConsentsResponse(consents.stream().map(consent -> new Item(consent.type(),
                    consent.type().optional(), consent.policyVersion(), consent.granted(), consent.recordedAt()))
                    .toList());
        }
    }

    /** @param recordedAt null for an optional consent never given */
    public record Item(ConsentType type, boolean optional, String policyVersion, boolean granted, Instant recordedAt) {
    }
}
