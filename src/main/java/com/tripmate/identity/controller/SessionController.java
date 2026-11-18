package com.tripmate.identity.controller;

import com.tripmate.identity.dto.AccessGrantResponse;
import com.tripmate.identity.dto.ActiveSessionListResponse;
import com.tripmate.identity.dto.ActiveSessionMapper;
import com.tripmate.identity.service.SessionProblem;
import com.tripmate.identity.service.SessionService;
import com.tripmate.identity.service.SessionTokens;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.ApiProblems;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.security.CsrfHeaderFilter;
import com.tripmate.platform.web.ClientAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refresh, logout, "revoke all devices" and the sessions list.
 *
 * <p>Refresh is the only route that reads the refresh cookie, and it is
 * guarded against cross-site use by {@link CsrfHeaderFilter}. The other three
 * are authenticated by the bearer access token, which a browser never
 * attaches by itself, and act only on the caller's own sessions: the user and
 * session IDs come from the verified token, never from the request.
 */
@RestController
@RequestMapping(SessionController.BASE)
@Tag(name = "Sessions", description = "Refresh the access token, sign out, and manage signed-in devices")
public class SessionController {

    public static final String BASE = "/auth";

    public static final String REFRESH = "/refresh";

    public static final String LOGOUT = "/logout";

    public static final String SESSIONS = "/sessions";

    public static final String REVOKE_ALL = "/sessions/revoke-all";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final SessionService sessions;

    private final ActiveSessionMapper sessionMapper;

    public SessionController(SessionService sessions, ActiveSessionMapper sessionMapper) {
        this.sessions = sessions;
        this.sessionMapper = sessionMapper;
    }

    @PostMapping(REFRESH)
    @Operation(summary = "Exchange the refresh cookie for a new access token",
            description = "Reads the HttpOnly tm_refresh cookie, which only this path receives, and rotates it: "
                    + "the response sets a new cookie and the old value stops working. Presenting a value that "
                    + "was already rotated ends the whole session on every device it reached. A second request "
                    + "with the just-replaced value, within a few seconds, gets an access token without a new "
                    + "cookie, so two tabs refreshing at once do not sign the user out.")
    @Parameter(in = ParameterIn.HEADER, name = CsrfHeaderFilter.HEADER, required = true,
            description = "Must be exactly 1. A cross-site page cannot set it without passing CORS.",
            schema = @Schema(type = "string", allowableValues = CsrfHeaderFilter.VALUE))
    @ApiResponse(responseCode = "200", description = "New access token",
            headers = @Header(name = HttpHeaders.SET_COOKIE,
                    description = "tm_refresh: the rotated refresh token (absent when the cookie is unchanged)"),
            content = @Content(schema = @Schema(implementation = AccessGrantResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "invalid_refresh_token: missing, malformed, expired, revoked or reused. Clears the cookie.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "csrf_rejected, or a cross-origin request from an origin "
            + "that is not allowed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many refreshes from this IP",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Sessions cannot be checked right now. The cookie is "
            + "unchanged and still valid; retry later.",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<?> refresh(
            @Parameter(hidden = true) @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken,
            HttpServletRequest http) {
        SessionTokens session;
        try {
            session = sessions.refresh(refreshToken, ClientAddress.of(http));
        } catch (ApiProblemException e) {
            if (e.type() != SessionProblem.INVALID_REFRESH_TOKEN) {
                throw e;
            }
            // A dead cookie is dropped so the browser stops sending it. Only on
            // this 401: a 403 or 503 leaves a possibly valid cookie alone.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, RefreshCookie.clear().toString())
                    .body(ApiProblems.forType(e.type()));
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (session.refreshToken() != null) {
            response.header(HttpHeaders.SET_COOKIE,
                    RefreshCookie.issue(session.refreshToken(), session.refreshMaxAge()).toString());
        }
        return response.body(AccessGrantResponse.of(session));
    }

    @PostMapping(LOGOUT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Sign out of this session",
            description = "Ends the session the access token belongs to. Its refresh token and every access token "
                    + "issued from it stop working immediately, not when they expire. Clears the refresh cookie.")
    @ApiResponse(responseCode = "204", description = "Signed out",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = "Clears tm_refresh"))
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Sessions cannot be changed right now; nothing was changed",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        sessions.logout(SessionPrincipal.of(token));
        return signedOut();
    }

    @PostMapping(REVOKE_ALL)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Sign out of every session on every device",
            description = "Ends all of the caller's sessions, this one included. Every refresh token and access "
                    + "token of the account stops working immediately.")
    @ApiResponse(responseCode = "204", description = "All sessions ended",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = "Clears tm_refresh"))
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Sessions cannot be changed right now; nothing was changed",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> revokeAll(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        sessions.revokeAll(SessionPrincipal.of(token));
        return signedOut();
    }

    @GetMapping(SESSIONS)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "List the caller's signed-in sessions",
            description = "Only the caller's own live sessions; the one making this request has current = true.")
    @ApiResponse(responseCode = "200", description = "The caller's sessions")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Sessions cannot be checked right now",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ActiveSessionListResponse list(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        SessionPrincipal caller = SessionPrincipal.of(token);
        return sessionMapper.toListResponse(sessions.list(caller), caller.sessionId());
    }

    private static ResponseEntity<Void> signedOut() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.clear().toString())
                .build();
    }
}
