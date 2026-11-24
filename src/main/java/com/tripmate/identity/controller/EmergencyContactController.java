package com.tripmate.identity.controller;

import com.tripmate.identity.dto.EmergencyContactMapper;
import com.tripmate.identity.dto.EmergencyContactRequest;
import com.tripmate.identity.dto.EmergencyContactResponse;
import com.tripmate.identity.service.EmergencyContactService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own emergency contact. No user ID anywhere: there is nobody else's to reach. */
@RestController
@RequestMapping(CurrentUserController.BASE + EmergencyContactController.PATH)
@Tag(name = "Account", description = "The signed-in user's own account")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class EmergencyContactController {

    public static final String PATH = "/me/emergency-contact";

    private final EmergencyContactService contacts;

    private final EmergencyContactMapper mapper;

    public EmergencyContactController(EmergencyContactService contacts, EmergencyContactMapper mapper) {
        this.contacts = contacts;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The caller's emergency contact", description = "The number comes back masked")
    @ApiResponse(responseCode = "200", description = "The contact, or contact: null when none is set")
    public EmergencyContactResponse get(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(contacts.mine(SessionPrincipal.of(token)).orElse(null));
    }

    @PutMapping
    @Operation(summary = "Set or replace the caller's emergency contact",
            description = "Needed before arranging a meet-up. They are alerted only if the caller goes quiet at one.")
    @ApiResponse(responseCode = "200", description = "The contact as stored, number masked")
    @ApiResponse(responseCode = "400", description = "invalid_phone_number, emergency_contact_is_you, or a malformed "
            + "request", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemDetail.class)))
    public EmergencyContactResponse set(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                        @Valid @RequestBody EmergencyContactRequest request) {
        return mapper.toResponse(contacts.set(SessionPrincipal.of(token), request.name(), request.relationship(),
                request.phoneNumber()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the caller's emergency contact")
    @ApiResponse(responseCode = "204", description = "Removed, or there was none")
    public void remove(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        contacts.remove(SessionPrincipal.of(token));
    }
}
