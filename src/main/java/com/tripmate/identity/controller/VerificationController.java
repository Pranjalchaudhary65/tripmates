package com.tripmate.identity.controller;

import com.tripmate.identity.dto.PhoneCodeRequest;
import com.tripmate.identity.dto.PhoneCodeSentResponse;
import com.tripmate.identity.dto.PhoneConfirmRequest;
import com.tripmate.identity.dto.SelfieChallengeResponse;
import com.tripmate.identity.dto.VerificationMapper;
import com.tripmate.identity.dto.VerificationOverviewResponse;
import com.tripmate.identity.service.PhoneVerificationService;
import com.tripmate.identity.service.SelfieVerificationService;
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
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * The caller's own verification tiers. No route takes a user ID: the account
 * is always the one the access token names. Every step is optional; the app
 * works at any tier.
 */
@RestController
@RequestMapping(VerificationController.BASE)
@Tag(name = "Verification", description = "Phone and selfie tiers")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = VerificationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many attempts for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = VerificationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Sessions or limits cannot be checked right now",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = VerificationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class VerificationController {

    public static final String BASE = "/verification";

    public static final String PHONE = "/phone";

    public static final String PHONE_CONFIRM = "/phone/confirm";

    public static final String SELFIE_CHALLENGE = "/selfie/challenge";

    public static final String SELFIE = "/selfie";

    static final String PROBLEM_JSON = "application/problem+json";

    private final VerificationOverviewService overviews;

    private final PhoneVerificationService phones;

    private final SelfieVerificationService selfies;

    private final VerificationMapper mapper;

    public VerificationController(VerificationOverviewService overviews, PhoneVerificationService phones,
                                  SelfieVerificationService selfies, VerificationMapper mapper) {
        this.overviews = overviews;
        this.phones = phones;
        this.selfies = selfies;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The verification centre: tier, each step, profile photo, vouches")
    @ApiResponse(responseCode = "200", description = "The caller's verification state")
    public VerificationOverviewResponse overview(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(overviews.overview(SessionPrincipal.of(token)));
    }

    @PostMapping(PHONE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a code to a phone number",
            description = "Replaces any unconfirmed number. The code is valid for 10 minutes; a new one can be "
                    + "requested after resendAfter seconds, up to 5 a day.")
    @ApiResponse(responseCode = "202", description = "A code is on its way")
    @ApiResponse(responseCode = "400", description = "invalid_phone_number, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "phone_already_verified",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public PhoneCodeSentResponse sendPhoneCode(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                               @Valid @RequestBody PhoneCodeRequest request) {
        return mapper.toResponse(phones.sendCode(SessionPrincipal.of(token), request.phoneNumber()));
    }

    @PostMapping(PHONE_CONFIRM)
    @Operation(summary = "Confirm the phone number with its code", description = "Raises the account to PHONE.")
    @ApiResponse(responseCode = "200", description = "Confirmed; the updated verification state")
    @ApiResponse(responseCode = "400", description = "invalid_phone_code, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "phone_already_verified or phone_in_use",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VerificationOverviewResponse confirmPhone(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                     @Valid @RequestBody PhoneConfirmRequest request) {
        SessionPrincipal caller = SessionPrincipal.of(token);
        phones.confirm(caller, request.verificationId(), request.code());
        return mapper.toResponse(overviews.overview(caller));
    }

    @PostMapping(SELFIE_CHALLENGE)
    @Operation(summary = "Start a selfie check",
            description = "Needs a verified phone and a profile photo. Returns a prompt to follow in the second "
                    + "frame, and an ID good for one submission within expiresIn seconds.")
    @ApiResponse(responseCode = "200", description = "The check and its prompt")
    @ApiResponse(responseCode = "409", description = "selfie_already_verified, selfie_review_pending, "
            + "phone_verification_required or profile_photo_required",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public SelfieChallengeResponse startSelfie(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return mapper.toResponse(selfies.start(SessionPrincipal.of(token)));
    }

    @PostMapping(path = SELFIE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Submit the two frames of a selfie check",
            description = "JPEG or PNG, up to 5 MB each. A clear match is approved at once and raises the account "
                    + "to SELFIE_MATCH; a clear mismatch or a failed liveness check is rejected; anything in "
                    + "between goes to a reviewer (status PENDING). The image is deleted 7 days after submission "
                    + "whatever the outcome.")
    @ApiResponse(responseCode = "200", description = "The outcome, in the updated verification state")
    @ApiResponse(responseCode = "400", description = "liveness_challenge_invalid or invalid_image",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "selfie_already_verified, selfie_review_pending, "
            + "phone_verification_required or profile_photo_required",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "A frame is larger than 5 MB",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VerificationOverviewResponse submitSelfie(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                     @RequestPart("challengeId") String challengeId,
                                                     @RequestPart("neutralFrame") MultipartFile neutralFrame,
                                                     @RequestPart("promptFrame") MultipartFile promptFrame)
            throws IOException {
        SessionPrincipal caller = SessionPrincipal.of(token);
        selfies.submit(caller, challengeId, Uploads.bytesOf(neutralFrame), Uploads.bytesOf(promptFrame));
        return mapper.toResponse(overviews.overview(caller));
    }
}
