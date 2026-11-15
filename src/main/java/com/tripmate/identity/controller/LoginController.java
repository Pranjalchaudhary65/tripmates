package com.tripmate.identity.controller;

import com.tripmate.identity.dto.LoginRequest;
import com.tripmate.identity.dto.LoginResponse;
import com.tripmate.identity.service.LoginClient;
import com.tripmate.identity.service.LoginResult;
import com.tripmate.identity.service.LoginService;
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
@Tag(name = "Login", description = "Check an email and password")
public class LoginController {

    public static final String BASE = "/auth";

    public static final String LOGIN = "/login";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final LoginService loginService;

    public LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping(LOGIN)
    @Operation(summary = "Log in with email and password",
            description = "Unknown email, wrong password, unverified email and locked account all get the same 401. "
                    + "Limited per client IP and per email address. A successful login sets an HttpOnly device "
                    + "cookie so this browser is recognised next time; no session or token is issued yet.")
    @ApiResponse(responseCode = "200", description = "Email and password are correct",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = "tm_device: identifies this browser"))
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
        LoginClient client = new LoginClient(ClientAddress.of(http), http.getHeader(HttpHeaders.USER_AGENT),
                DeviceCookie.valueOrNull(deviceCookie));
        LoginResult result = loginService.login(request, client);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, DeviceCookie.issue(result.deviceId()).toString())
                .body(LoginResponse.of(result));
    }
}
