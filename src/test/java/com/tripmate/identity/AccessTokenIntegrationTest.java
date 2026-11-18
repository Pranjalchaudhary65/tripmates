package com.tripmate.identity;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.SignedJWT;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access token: what login issues, and every way of presenting a token
 * the server did not issue, or no longer honours, ending in the same 401.
 */
@IntegrationTest
class AccessTokenIntegrationTest {

    private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private LocalHttp http;

    private SessionApi api;

    private String email;

    private SessionApi.Session session;

    @BeforeEach
    void signIn() {
        http = new LocalHttp(port);
        api = new SessionApi(http);
        email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        session = api.signIn(email, STRONG_PASSWORD);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void loginReturnsAnAccessTokenThatOpensAProtectedRoute() throws Exception {
        HttpResponse<String> login = new LoginApi(http).login(email, STRONG_PASSWORD);

        DocumentContext body = JsonPath.parse(login.body());
        assertThat(body.read("$.status", String.class)).isEqualTo("AUTHENTICATED");
        assertThat(body.read("$.session.tokenType", String.class)).isEqualTo("Bearer");
        assertThat(body.read("$.session.expiresIn", Integer.class)).isEqualTo(900);
        assertThat(body.read("$.session.user.id", String.class)).isEqualTo(userId());
        assertThat(body.read("$.session.user.role", String.class)).isEqualTo("USER");
        assertThat(body.read("$.session.user.verificationTier", String.class)).isEqualTo("EMAIL");
        assertThat(login.body()).doesNotContain(SessionApi.refreshToken(login));

        SignedJWT jwt = SignedJWT.parse(body.read("$.session.accessToken", String.class));
        JWSHeader header = jwt.getHeader();
        assertThat(header.getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(header.getType().getType()).isEqualTo("at+jwt");
        assertThat(header.getKeyID()).isEqualTo(ForgedTokens.kid(ForgedTokens.CURRENT_KEY));
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId());
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("amr")).containsExactly("pwd");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("USER");

        HttpResponse<String> me = api.me(body.read("$.session.accessToken", String.class));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo(email);
        assertThat(JsonPath.<String>read(me.body(), "$.verificationTier")).isEqualTo("EMAIL");
        assertThat(me.body()).doesNotContain("password", "argon2");
    }

    @Test
    void loginSetsAHardenedRefreshCookieScopedToTheRefreshRoute() {
        HttpResponse<String> login = new LoginApi(http).login(email, STRONG_PASSWORD);

        assertThat(SessionApi.refreshSetCookie(login)).hasValueSatisfying(cookie -> assertThat(cookie)
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth/refresh",
                        "Max-Age=2592000"));
        assertThat(LoginApi.deviceSetCookie(login)).isPresent();
    }

    @Test
    void noTokenGetsABareBearerChallenge() {
        HttpResponse<String> response = http.get(SessionApi.ME);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
    }

    @Test
    void aTokenStopsWorkingOnceItExpires() {
        clock.advance(Duration.ofMinutes(14));
        assertThat(api.me(session.accessToken()).statusCode()).isEqualTo(200);

        // 15 minutes plus the 30-second clock skew allowance.
        clock.advance(Duration.ofMinutes(1).plusSeconds(31));
        assertRejected(api.me(session.accessToken()));
    }

    @Test
    void changingAnyClaimBreaksTheSignature() {
        String[] parts = session.accessToken().split("\\.");
        Map<String, Object> elevated = ForgedTokens.claimsOf(session.accessToken(), c -> c.put("role", "ADMIN"));
        String elevatedPayload = ForgedTokens.hs256(ForgedTokens.header("HS256", null), elevated,
                ForgedTokens.randomKey()).split("\\.")[1];

        // The server's own header and signature, around a payload that now says ADMIN.
        assertRejected(api.me(parts[0] + "." + elevatedPayload + "." + parts[2]));
        assertRejected(api.me(parts[0] + "." + parts[1] + "." + flipLastChar(parts[2])));
    }

    @Test
    void aTokenSignedWithAnyOtherKeyIsRejectedEvenUnderTheRightKid() {
        Map<String, Object> claims = ForgedTokens.claimsOf(session.accessToken());

        assertRejected(api.me(ForgedTokens.hs256(
                ForgedTokens.header("HS256", ForgedTokens.kid(ForgedTokens.CURRENT_KEY)), claims,
                ForgedTokens.randomKey())));
        assertRejected(api.me(ForgedTokens.hs256(
                ForgedTokens.header("HS256", "0000000000000000"), claims, ForgedTokens.CURRENT_KEY)));
        assertRejected(api.me(ForgedTokens.hs256(
                ForgedTokens.header("HS256", null), claims, ForgedTokens.CURRENT_KEY)));
    }

    @Test
    void theRotatedOutKeyIsStillAcceptedUnderItsOwnKid() {
        Map<String, Object> claims = ForgedTokens.claimsOf(session.accessToken());
        String signedWithPrevious = ForgedTokens.hs256(
                ForgedTokens.header("HS256", ForgedTokens.kid(ForgedTokens.PREVIOUS_KEY)), claims,
                ForgedTokens.PREVIOUS_KEY);

        assertThat(api.me(signedWithPrevious).statusCode()).isEqualTo(200);
        // The key must match its own kid, not just be one of the configured keys.
        assertRejected(api.me(ForgedTokens.hs256(
                ForgedTokens.header("HS256", ForgedTokens.kid(ForgedTokens.CURRENT_KEY)), claims,
                ForgedTokens.PREVIOUS_KEY)));
    }

    @Test
    void onlyHs256IsAcceptedWhateverTheSignatureReallyIs() {
        Map<String, Object> claims = ForgedTokens.claimsOf(session.accessToken());
        String kid = ForgedTokens.kid(ForgedTokens.CURRENT_KEY);

        assertRejected(api.me(ForgedTokens.unsigned(claims)));
        assertRejected(api.me(ForgedTokens.hmac(ForgedTokens.header("HS384", kid), claims,
                ForgedTokens.CURRENT_KEY, "HmacSHA384")));
        assertRejected(api.me(ForgedTokens.hmac(ForgedTokens.header("HS512", kid), claims,
                ForgedTokens.CURRENT_KEY, "HmacSHA512")));
        // Algorithm confusion: claims to be RS256 so a careless verifier would
        // treat the HMAC key as an RSA public key.
        assertRejected(api.me(ForgedTokens.hs256(ForgedTokens.header("RS256", kid), claims,
                ForgedTokens.CURRENT_KEY)));
        assertRejected(api.me(ForgedTokens.hs256(ForgedTokens.header("none", kid), claims,
                ForgedTokens.CURRENT_KEY)));
    }

    @Test
    void aCorrectlySignedTokenOfTheWrongTypeOrWithWrongClaimsIsRejected() {
        String kid = ForgedTokens.kid(ForgedTokens.CURRENT_KEY);
        byte[] key = ForgedTokens.CURRENT_KEY;
        Map<String, Object> plainJwtHeader = ForgedTokens.header("HS256", kid);
        plainJwtHeader.put("typ", "JWT");

        assertRejected(api.me(ForgedTokens.hs256(plainJwtHeader, ForgedTokens.claimsOf(session.accessToken()), key)));
        for (Map<String, Object> claims : List.of(
                ForgedTokens.claimsOf(session.accessToken(), c -> c.put("iss", "someone-else")),
                ForgedTokens.claimsOf(session.accessToken(), c -> c.put("aud", List.of("another-api"))),
                ForgedTokens.claimsOf(session.accessToken(), c -> c.remove("exp")),
                ForgedTokens.claimsOf(session.accessToken(), c -> c.remove("sid")),
                ForgedTokens.claimsOf(session.accessToken(), c -> c.put("sid", "not-a-uuid")),
                ForgedTokens.claimsOf(session.accessToken(), c -> c.put("role", "SUPERUSER")),
                ForgedTokens.claimsOf(session.accessToken(), c -> c.remove("jti")),
                ForgedTokens.claimsOf(session.accessToken(),
                        c -> c.put("exp", Instant.now().minusSeconds(3600).getEpochSecond())))) {
            assertRejected(api.me(ForgedTokens.hs256(ForgedTokens.header("HS256", kid), claims, key)));
        }
    }

    @Test
    void aStaleTokenSentToLoginIsIgnoredRatherThanBlockingTheLogin() {
        HttpResponse<String> response = new LoginApi(http).login(email, STRONG_PASSWORD,
                "Authorization", "Bearer not-a-token");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void garbageInTheAuthorizationHeaderIsA401NotA500() {
        for (String value : List.of("Bearer", "Bearer a.b", "Bearer a.b.c", "Bearer a b", "Basic dXNlcjpwYXNz",
                "Bearer " + "x".repeat(5000))) {
            HttpResponse<String> response = http.get(SessionApi.ME, "Authorization", value);
            assertThat(response.statusCode()).as(value).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type")).as(value)
                    .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        }
    }

    private static void assertRejected(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue(INVALID_TOKEN_CHALLENGE);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).doesNotContain("Jwt", "signature", "expired", "kid");
    }

    private String userId() {
        return jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
    }

    private static String flipLastChar(String value) {
        char last = value.charAt(value.length() - 1);
        return value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');
    }
}
