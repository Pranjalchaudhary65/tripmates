package com.tripmate.identity.controller;

import com.tripmate.identity.dto.EmailVerifiedResponse;
import com.tripmate.identity.dto.RegistrationRequest;
import com.tripmate.identity.dto.ResendVerificationRequest;
import com.tripmate.identity.dto.VerificationChallengeResponse;
import com.tripmate.identity.dto.VerifyEmailRequest;
import com.tripmate.identity.service.IdentityProblem;
import com.tripmate.identity.service.OtpService;
import com.tripmate.identity.service.RegistrationService;
import com.tripmate.platform.error.ApiProblemException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous sign-up and email verification. These are the only identity
 * routes SecurityConfig opens without authentication.
 */
@RestController
@RequestMapping(RegistrationController.BASE)
@Tag(name = "Registration", description = "Sign up and verify an email address")
public class RegistrationController {

    public static final String BASE = "/auth";

    public static final String REGISTER = "/register";

    public static final String VERIFY_EMAIL = "/verify-email";

    public static final String RESEND_VERIFICATION = "/verify-email/resend";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final RegistrationService registrationService;

    private final OtpService otpService;

    public RegistrationController(RegistrationService registrationService, OtpService otpService) {
        this.registrationService = registrationService;
        this.otpService = otpService;
    }

    @PostMapping(REGISTER)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Register a new account",
            description = "Always answers 202 with a verificationId once the request passes validation, "
                    + "whether or not the email already has an account. A code is emailed to a new or "
                    + "still-unverified address; a verified address is sent a notice instead.")
    @ApiResponse(responseCode = "202", description = "Check the inbox for a verification code")
    @ApiResponse(responseCode = "400",
            description = "Validation failed, under 18, or the password is breached or contains personal details",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VerificationChallengeResponse register(@Valid @RequestBody RegistrationRequest request) {
        return VerificationChallengeResponse.of(registrationService.register(request));
    }

    @PostMapping(VERIFY_EMAIL)
    @Operation(summary = "Verify an email address with the emailed code",
            description = "Codes expire and allow a limited number of attempts. Every failure gives the same answer.")
    @ApiResponse(responseCode = "200", description = "Email verified; the account is now at the EMAIL tier")
    @ApiResponse(responseCode = "400", description = "Invalid, expired or used code, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public EmailVerifiedResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        if (!otpService.verifyEmail(request.verificationId(), request.code())) {
            throw new ApiProblemException(IdentityProblem.INVALID_VERIFICATION_CODE);
        }
        return EmailVerifiedResponse.verified();
    }

    @PostMapping(RESEND_VERIFICATION)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request a new verification code",
            description = "New codes are subject to a per-account cooldown and daily cap. "
                    + "Always answers 202; use the returned verificationId from now on.")
    @ApiResponse(responseCode = "202", description = "Use the returned verificationId with the newest code")
    @ApiResponse(responseCode = "400", description = "Malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public VerificationChallengeResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        return VerificationChallengeResponse.of(otpService.resendEmailVerification(request.verificationId()));
    }
}
