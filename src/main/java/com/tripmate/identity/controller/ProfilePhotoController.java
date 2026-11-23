package com.tripmate.identity.controller;

import com.tripmate.identity.dto.VerificationMapper;
import com.tripmate.identity.dto.VerificationOverviewResponse;
import com.tripmate.identity.service.ProfilePhotoService;
import com.tripmate.identity.service.VerificationOverviewService;
import com.tripmate.identity.token.SessionPrincipal;
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
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** The caller's own profile photo; there is no ID in the path to swap for someone else's. */
@RestController
@RequestMapping(CurrentUserController.BASE)
@Tag(name = "Account", description = "The signed-in user's own account")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProfilePhotoController {

    public static final String PROFILE_PHOTO = CurrentUserController.ME + "/profile-photo";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final ProfilePhotoService photos;

    private final VerificationOverviewService overviews;

    private final VerificationMapper mapper;

    public ProfilePhotoController(ProfilePhotoService photos, VerificationOverviewService overviews,
                                  VerificationMapper mapper) {
        this.photos = photos;
        this.overviews = overviews;
        this.mapper = mapper;
    }

    @PutMapping(path = PROFILE_PHOTO, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace the profile photo",
            description = "JPEG or PNG, up to 5 MB, 64 to 4096 pixels a side. Stored re-encoded, with all "
                    + "metadata removed. Replacing it drops a SELFIE_MATCH account back to PHONE, and withdraws a "
                    + "selfie waiting for review, until a new selfie matches the new photo.")
    @ApiResponse(responseCode = "200", description = "The updated verification state, with a link to the photo")
    @ApiResponse(responseCode = "400", description = "invalid_image",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Larger than 5 MB",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many uploads for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VerificationOverviewResponse replace(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                @RequestPart("photo") MultipartFile photo) throws IOException {
        SessionPrincipal caller = SessionPrincipal.of(token);
        photos.replace(caller, Uploads.bytesOf(photo));
        return mapper.toResponse(overviews.overview(caller));
    }
}
