package com.tripmate.identity.controller;

import com.tripmate.identity.dto.LoginRequest;
import com.tripmate.identity.dto.LoginResponse;
import com.tripmate.identity.dto.SecondFactorLoginRequest;
import com.tripmate.identity.service.LoginClient;
import com.tripmate.identity.service.LoginResult;
import com.tripmate.identity.service.LoginService;
import com.tripmate.identity.service.SecondFactorLoginService;
import com.tripmate.platform.web.ClientAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(LoginController.BASE)
@Tag(name = "Login", description = "Sign in with an email and password")
public class LoginController {

    public static final String BASE = "/auth";

    public static final String LOGIN = "/login";

    public static final String SECOND_FACTOR = "/login/second-factor";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final LoginService loginService;

    private final SecondFactorLoginService secondFactorLogins;

    public LoginController(LoginService loginService, SecondFactorLoginService secondFactorLogins) {
        this.loginService = loginService;
        this.secondFactorLogins = secondFactorLogins;
    }

    @PostMapping(LOGIN)
    @Operation(summary = "Log in with email and password",
            description = "Unknown email, wrong password, unverified email and locked account all get the same 401. "
                    + "Limited per client IP and per email address. Branch on status. AUTHENTICATED opens a "
                    + "session: the body carries a 15-minute access token, and HttpOnly cookies carry the refresh "
                    + "token and a device ID that lets this browser be recognised next time. "
                    + "SECOND_FACTOR_REQUIRED (accounts with 2FA, and every admin) carries only a challenge for "
                    + "/auth/login/second-factor: no token and no cookie.")
    @ApiResponse(responseCode = "200", description = "Signed in, or a second factor is required",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = "Only when status is AUTHENTICATED. "
                    + "tm_refresh: the refresh token, sent back only to /auth/refresh; tm_device: identifies "
                    + "this browser"))
    @ApiResponse(responseCode = "400", description = "Malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401",
            description = "invalid_credentials for every refusal, or captcha_required after repeated failures",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many attempts from this IP or for this email",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The attempt limits cannot be checked right now",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            @Parameter(hidden = true) @CookieValue(name = DeviceCookie.NAME, required = false) String deviceCookie,
            HttpServletRequest http) {
        return respond(loginService.login(request, client(deviceCookie, http)));
    }

    @PostMapping(SECOND_FACTOR)
    @Operation(summary = "Finish a login with a TOTP or backup code",
            description = "For a login that answered SECOND_FACTOR_REQUIRED. Send its challengeId and the current "
                    + "code from the authenticator app, or an unused backup code. A challenge takes a few codes, "
                    + "then the password has to be entered again; repeated wrong codes also lock the second step "
                    + "for a while, whatever the challenge. Only on success are the session and cookies issued, "
                    + "and the access token's amr then includes otp.")
    @ApiResponse(responseCode = "200", description = "Signed in; status is AUTHENTICATED",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = "tm_refresh and tm_device, as for login"))
    @ApiResponse(responseCode = "400", description = "Malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "invalid_second_factor for a wrong, replayed or used code, "
            + "or login_challenge_expired when the login has to start again from the password",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many attempts from this IP",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The attempt limits cannot be checked right now",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<LoginResponse> secondFactor(
            @Valid @RequestBody SecondFactorLoginRequest request,
            @Parameter(hidden = true) @CookieValue(name = DeviceCookie.NAME, required = false) String deviceCookie,
            HttpServletRequest http) {
        return respond(secondFactorLogins.complete(request.challengeId(), request.code(), client(deviceCookie, http)));
    }

    private static LoginClient client(String deviceCookie, HttpServletRequest http) {
        return new LoginClient(ClientAddress.of(http), http.getHeader(HttpHeaders.USER_AGENT),
                DeviceCookie.valueOrNull(deviceCookie));
    }

    /** Cookies only with a session: a challenge sets nothing the browser could replay. */
    private static ResponseEntity<LoginResponse> respond(LoginResult result) {
        if (!result.isSignedIn()) {
            return ResponseEntity.ok(LoginResponse.of(result));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, DeviceCookie.issue(result.deviceId()).toString())
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.issue(result.session().refreshToken(),
                        result.session().refreshMaxAge()).toString())
                .body(LoginResponse.of(result));
    }
}
