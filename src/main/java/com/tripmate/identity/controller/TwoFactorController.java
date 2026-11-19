package com.tripmate.identity.controller;

import com.tripmate.identity.dto.BackupCodesResponse;
import com.tripmate.identity.dto.TwoFactorChangeRequest;
import com.tripmate.identity.dto.TwoFactorConfirmRequest;
import com.tripmate.identity.dto.TwoFactorSetupRequest;
import com.tripmate.identity.dto.TwoFactorSetupResponse;
import com.tripmate.identity.dto.TwoFactorStatusResponse;
import com.tripmate.identity.service.TwoFactorService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.web.ClientAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own second factor. No route takes a user ID: the account is
 * always the one the access token names, so there is no one else's to try.
 */
@RestController
@RequestMapping(TwoFactorController.BASE)
@Tag(name = "Two-factor authentication", description = "TOTP authenticator apps and backup codes")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = TwoFactorController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Sessions or limits cannot be checked right now",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = TwoFactorController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class TwoFactorController {

    public static final String BASE = "/auth/2fa";

    public static final String SETUP = "/setup";

    public static final String CONFIRM = "/confirm";

    public static final String DISABLE = "/disable";

    public static final String BACKUP_CODES = "/backup-codes";

    static final String PROBLEM_JSON = "application/problem+json";

    private final TwoFactorService twoFactor;

    public TwoFactorController(TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
    }

    @GetMapping
    @Operation(summary = "Whether 2FA is on, and how many backup codes are left")
    @ApiResponse(responseCode = "200", description = "The caller's 2FA state")
    public TwoFactorStatusResponse status(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return TwoFactorStatusResponse.of(twoFactor.status(SessionPrincipal.of(token)));
    }

    @PostMapping(SETUP)
    @Operation(summary = "Start setting up an authenticator app",
            description = "Needs the password. Returns a new secret and its otpauth:// URI, once; the secret is "
                    + "stored encrypted and never returned again. Nothing changes for login until /confirm. "
                    + "Calling this again before confirming replaces the secret.")
    @ApiResponse(responseCode = "200", description = "The new secret")
    @ApiResponse(responseCode = "400", description = "reauthentication_failed (wrong password), or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "two_factor_already_enabled",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many 2FA changes for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public TwoFactorSetupResponse setup(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                        @Valid @RequestBody TwoFactorSetupRequest request) {
        return TwoFactorSetupResponse.of(twoFactor.setup(SessionPrincipal.of(token), request.password()));
    }

    @PostMapping(CONFIRM)
    @Operation(summary = "Turn 2FA on with the first code from the app",
            description = "Returns ten backup codes, shown this once. Every other session of the account is "
                    + "signed out and the owner is emailed. This session stays signed in.")
    @ApiResponse(responseCode = "200", description = "2FA is on; the backup codes")
    @ApiResponse(responseCode = "400", description = "invalid_totp_code, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "two_factor_already_enabled or two_factor_setup_not_started",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many 2FA changes for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public BackupCodesResponse confirm(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @Valid @RequestBody TwoFactorConfirmRequest request,
                                       HttpServletRequest http) {
        return new BackupCodesResponse(twoFactor.confirm(SessionPrincipal.of(token), request.code(),
                ClientAddress.of(http)));
    }

    @PostMapping(DISABLE)
    @Operation(summary = "Turn 2FA off",
            description = "Needs the password and a current code or unused backup code. Deletes the secret and "
                    + "the backup codes, signs out every other session and emails the owner. Not allowed for "
                    + "admin accounts.")
    @ApiResponse(responseCode = "204", description = "2FA is off")
    @ApiResponse(responseCode = "400", description = "reauthentication_failed, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "two_factor_required_for_admin",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "two_factor_not_enabled",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many 2FA changes for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> disable(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                        @Valid @RequestBody TwoFactorChangeRequest request,
                                        HttpServletRequest http) {
        twoFactor.disable(SessionPrincipal.of(token), request.password(), request.code(), ClientAddress.of(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(BACKUP_CODES)
    @Operation(summary = "Replace the backup codes",
            description = "Needs the password and a current code or unused backup code. Returns ten new codes, "
                    + "shown this once; every old code stops working. The owner is emailed.")
    @ApiResponse(responseCode = "200", description = "The new backup codes")
    @ApiResponse(responseCode = "400", description = "reauthentication_failed, or malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "two_factor_not_enabled",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many 2FA changes for this account",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public BackupCodesResponse regenerateBackupCodes(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                     @Valid @RequestBody TwoFactorChangeRequest request,
                                                     HttpServletRequest http) {
        return new BackupCodesResponse(twoFactor.regenerateBackupCodes(SessionPrincipal.of(token),
                request.password(), request.code(), ClientAddress.of(http)));
    }
}
