package com.tripmate.platform.security;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.LoginApi;
import com.tripmate.identity.RegistrationApi;
import com.tripmate.identity.SessionApi;
import com.tripmate.identity.TwoFactorApi;
import com.tripmate.identity.VerificationApi;
import com.tripmate.platform.error.ProblemErrorController;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.LocalHttp.Part;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import com.tripmate.support.ProblemScenarioController;
import com.tripmate.support.TestAuthenticator;
import com.tripmate.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every route the application maps, each with its access rule, and one check
 * per rule of what an anonymous caller, a non-owner and the owner get.
 *
 * <ul>
 *   <li>{@link Access#PUBLIC}: reaches its handler whoever calls, and ignores
 *       any Authorization header, valid or not.</li>
 *   <li>{@link Access#SELF}: acts only on the caller's own account, so there is
 *       no ID to swap. Anonymous gets 401. A second user gets their own
 *       answer: the response mentions nothing of the owner, and the owner's
 *       account, 2FA state, sessions, verification state and vouches are
 *       unchanged after it. The owner gets the success status.</li>
 *   <li>{@link Access#OWNED}: a resource addressed by its own ID. Anonymous
 *       gets 401; another signed-in user gets 404, exactly as for an ID that
 *       does not exist (DESIGN.md, "Authorization tests"), and the owner's
 *       state is unchanged; the owner gets the success status.</li>
 *   <li>{@link Access#ADMIN}: anonymous gets 401; a signed-in user who is not
 *       an admin gets 403, as does a 2FA user who is not an admin and an admin
 *       whose session skipped the second factor; an admin signed in with a
 *       second factor gets the success status.</li>
 * </ul>
 *
 * <p>{@link #everyMappedRouteHasExactlyOneEntry()} reads the routes from
 * Spring MVC itself, so a new controller method without an entry below fails
 * the build instead of shipping unchecked.
 */
@IntegrationTest
class EndpointAuthorizationMatrixIntegrationTest {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(\\w+)}");

    private static final Pattern SIGNED_LINK = Pattern.compile("\"profilePhoto\":\\{[^}]*}");

    private static final String FORGED_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJub2JvZHkifQ.c2lnbmF0dXJl";

    static final List<Route> ROUTES = List.of(
            Route.anonymous("POST", "/api/v1/auth/register"),
            Route.anonymous("POST", "/api/v1/auth/verify-email"),
            Route.anonymous("POST", "/api/v1/auth/verify-email/resend"),
            Route.anonymous("POST", "/api/v1/auth/login"),
            Route.anonymous("POST", "/api/v1/auth/login/second-factor"),
            Route.anonymous("POST", "/api/v1/auth/refresh"),

            Route.self("GET", "/api/v1/users/me", Setup.SIGNED_IN, 200, Route.NO_BODY),
            Route.self("GET", "/api/v1/auth/sessions", Setup.SIGNED_IN, 200, Route.NO_BODY),
            Route.self("POST", "/api/v1/auth/logout", Setup.SIGNED_IN, 204, Route.NO_BODY),
            Route.self("POST", "/api/v1/auth/sessions/revoke-all", Setup.SIGNED_IN, 204, Route.NO_BODY),
            Route.self("GET", "/api/v1/auth/2fa", Setup.SIGNED_IN, 200, Route.NO_BODY),
            Route.self("POST", "/api/v1/auth/2fa/setup", Setup.SIGNED_IN, 200, actor -> """
                    {"password":"%s"}""".formatted(STRONG_PASSWORD)),
            Route.self("POST", "/api/v1/auth/2fa/confirm", Setup.SETUP_PENDING, 200, actor -> """
                    {"code":"%s"}""".formatted(actor.authenticator().nextCode())),
            Route.self("POST", "/api/v1/auth/2fa/disable", Setup.TWO_FACTOR_ON, 204, actor -> """
                    {"password":"%s","code":"%s"}""".formatted(STRONG_PASSWORD, actor.authenticator().nextCode())),
            Route.self("POST", "/api/v1/auth/2fa/backup-codes", Setup.TWO_FACTOR_ON, 200, actor -> """
                    {"password":"%s","code":"%s"}""".formatted(STRONG_PASSWORD, actor.authenticator().nextCode())),

            Route.self("GET", "/api/v1/verification", Setup.SIGNED_IN, 200, Route.NO_BODY),
            Route.self("POST", "/api/v1/verification/phone", Setup.SIGNED_IN, 202, actor -> """
                    {"phoneNumber":"%s"}""".formatted(VerificationApi.uniquePhone())),
            Route.self("POST", "/api/v1/verification/phone/confirm", Setup.PHONE_CODE_SENT, 200, actor -> """
                    {"verificationId":"%s","code":"%s"}""".formatted(actor.extra("verificationId"),
                    actor.extra("code"))),
            Route.self("POST", "/api/v1/verification/selfie/challenge", Setup.READY_FOR_SELFIE, 200, Route.NO_BODY),
            Route.self("POST", "/api/v1/verification/selfie", Setup.SELFIE_STARTED, 200, actor -> List.of(
                    Part.text("challengeId", actor.extra("challengeId")),
                    Part.jpeg("neutralFrame", TestImages.jpeg(0)), Part.jpeg("promptFrame", TestImages.jpeg(1)))),
            Route.self("PUT", "/api/v1/users/me/profile-photo", Setup.SIGNED_IN, 200, actor -> List.of(
                    Part.jpeg("photo", TestImages.profilePhoto()))),
            Route.self("GET", "/api/v1/vouches", Setup.SIGNED_IN, 200, Route.NO_BODY),
            Route.self("POST", "/api/v1/vouches", Setup.CAN_VOUCH, 201, actor -> """
                    {"vouchedUserId":"%s"}""".formatted(actor.extra("vouchTarget"))),
            Route.owned("DELETE", "/api/v1/vouches/{vouchId}", Setup.VOUCHING, "vouchId", 204),

            Route.admin("GET", "/api/v1/admin/users/{userId}", 200, Route.NO_BODY),
            Route.admin("GET", "/api/v1/admin/verifications", 200, Route.NO_BODY),
            Route.admin("POST", "/api/v1/admin/verifications/{verificationId}/decision", 200, actor -> """
                    {"decision":"REJECT"}"""),

            Route.anonymous("GET", "/api/v1/media/{token}"));

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private LocalHttp anonymous;

    @BeforeEach
    void setUp() {
        anonymous = new LocalHttp(port);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    static List<Route> routes() {
        return ROUTES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void anonymousNonOwnerAndOwnerGetWhatTheRuleSays(Route route) {
        switch (route.access()) {
            case PUBLIC -> checkPublic(route);
            case SELF -> checkSelf(route);
            case OWNED -> checkOwned(route);
            case ADMIN -> checkAdmin(route);
        }
    }

    @Test
    void everyMappedRouteHasExactlyOneEntry() {
        Set<String> declared = new TreeSet<>();
        for (Route route : ROUTES) {
            assertThat(declared.add(route.key())).as("duplicate entry %s", route.key()).isTrue();
        }
        Set<String> mapped = mappedRoutes(EndpointAuthorizationMatrixIntegrationTest::isProductionController);

        assertThat(uncovered(mapped)).as("mapped routes with no entry in ROUTES").isEmpty();
        assertThat(difference(declared, mapped)).as("entries in ROUTES for routes that are not mapped").isEmpty();
    }

    /** The check above fails for a real controller whose routes have no entries: the test-only one. */
    @Test
    void theCoverageCheckReportsEveryRouteOfAControllerWithNoEntries() {
        Set<String> withTestOnlyController = mappedRoutes(type ->
                isProductionController(type) || type == ProblemScenarioController.class);
        Set<String> testOnlyRoutes = mappedRoutes(type -> type == ProblemScenarioController.class);

        assertThat(testOnlyRoutes).isNotEmpty();
        assertThat(uncovered(withTestOnlyController)).isEqualTo(testOnlyRoutes);
    }

    /** Path variables get a value nothing was issued for, so the handler answers for a bad ID or token. */
    private void checkPublic(Route route) {
        String path = PATH_VARIABLE.matcher(route.path()).replaceAll("not-a-real-value");
        HttpResponse<String> plain = send(anonymous, route, path, null, "{}");
        HttpResponse<String> withForgedToken = send(anonymous, route, path, FORGED_TOKEN, "{}");

        assertReachedTheHandler(route, plain);
        assertReachedTheHandler(route, withForgedToken);
        assertThat(withForgedToken.statusCode()).as("%s with a token", route).isEqualTo(plain.statusCode());
    }

    private void checkSelf(Route route) {
        assertRefusedAsAnonymous(route, route.path());

        Actor owner = actor(route.setup());
        Actor nonOwner = actor(route.setup());
        Snapshot before = snapshot(owner);

        HttpResponse<String> asNonOwner = send(nonOwner.http(), route, route.path(), nonOwner.accessToken(),
                route.body().apply(nonOwner));

        assertThat(asNonOwner.statusCode()).as("%s as non-owner: %s", route, asNonOwner.body())
                .isEqualTo(route.ownerStatus());
        assertThat(asNonOwner.body()).as("%s as non-owner", route)
                .doesNotContain(owner.userId(), owner.email(), owner.sessionId());
        assertThat(snapshot(owner)).as("%s as non-owner left the owner's account alone", route).isEqualTo(before);

        HttpResponse<String> asOwner = send(owner.http(), route, route.path(), owner.accessToken(),
                route.body().apply(owner));
        assertThat(asOwner.statusCode()).as("%s as owner: %s", route, asOwner.body()).isEqualTo(route.ownerStatus());
    }

    /**
     * A resource addressed by its own ID: anonymous gets 401; anyone but its
     * owner gets the same 404 as for an ID that does not exist, and the
     * resource is untouched; the owner gets the success status.
     */
    private void checkOwned(Route route) {
        Actor owner = actor(route.setup());
        String path = expand(route.path(), Map.of(route.idVariable(), owner.extra(route.idVariable())));
        assertRefusedAsAnonymous(route, path);

        Actor nonOwner = actor(route.setup());
        Snapshot before = snapshot(owner);
        HttpResponse<String> asNonOwner = send(nonOwner.http(), route, path, nonOwner.accessToken(),
                route.body().apply(nonOwner));
        assertThat(asNonOwner.statusCode()).as("%s as non-owner: %s", route, asNonOwner.body()).isEqualTo(404);
        assertThat(asNonOwner.body()).as("%s as non-owner", route).contains("\"not_found\"");
        assertThat(snapshot(owner)).as("%s as non-owner left the owner's resource alone", route).isEqualTo(before);

        HttpResponse<String> unknown = send(owner.http(), route,
                expand(route.path(), Map.of(route.idVariable(), UUID.randomUUID().toString())),
                owner.accessToken(), route.body().apply(owner));
        assertThat(unknown.statusCode()).as("%s with an unknown ID", route).isEqualTo(404);
        assertThat(JsonPath.<String>read(unknown.body(), "$.code")).as("non-owner and unknown ID look the same")
                .isEqualTo(JsonPath.read(asNonOwner.body(), "$.code"));
        assertThat(JsonPath.<String>read(unknown.body(), "$.detail"))
                .isEqualTo(JsonPath.read(asNonOwner.body(), "$.detail"));

        HttpResponse<String> asOwner = send(owner.http(), route, path, owner.accessToken(), route.body().apply(owner));
        assertThat(asOwner.statusCode()).as("%s as owner: %s", route, asOwner.body()).isEqualTo(route.ownerStatus());
    }

    private void checkAdmin(Route route) {
        Actor user = actor(Setup.SIGNED_IN);
        String path = expand(route.path(), Map.of("userId", user.userId(),
                "verificationId", pendingSelfieOf(user.userId())));
        assertRefusedAsAnonymous(route, path);

        assertForbidden(route, user.http(), path, user.accessToken(), "a user");
        Actor userWithOtp = actor(Setup.TWO_FACTOR_ON);
        assertForbidden(route, userWithOtp.http(), path, signInWithSecondFactor(userWithOtp), "a user with otp in amr");

        Actor admin = actor(Setup.TWO_FACTOR_ON);
        jdbc.update("update users set role = 'ADMIN' where id = ?::uuid", admin.userId());
        String adminWithoutOtp = SessionApi.accessTokenOfRefresh(
                new SessionApi(admin.http()).refresh(admin.session().refreshToken()));
        assertForbidden(route, admin.http(), path, adminWithoutOtp, "an admin without otp in amr");

        HttpResponse<String> asAdmin = send(admin.http(), route, path, signInWithSecondFactor(admin),
                route.body().apply(admin));
        assertThat(asAdmin.statusCode()).as("%s as an admin with otp: %s", route, asAdmin.body())
                .isEqualTo(route.ownerStatus());
    }

    /** Stopped by the filter chain, before any handler: 401 with a bare Bearer challenge, and no problem code. */
    private void assertRefusedAsAnonymous(Route route, String path) {
        HttpResponse<String> response = send(anonymous, route, path, null, "{}");
        assertThat(response.statusCode()).as("%s anonymous", route).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).as("%s anonymous", route).hasValue("Bearer");
        assertThat(response.body()).as("%s anonymous", route).doesNotContain("\"code\"");

        HttpResponse<String> forged = send(anonymous, route, path, FORGED_TOKEN, "{}");
        assertThat(forged.statusCode()).as("%s with a forged token", route).isEqualTo(401);
        assertThat(forged.headers().firstValue("WWW-Authenticate")).as("%s with a forged token", route)
                .hasValue("Bearer error=\"invalid_token\"");
    }

    private void assertForbidden(Route route, LocalHttp http, String path, String accessToken, String who) {
        HttpResponse<String> response = send(http, route, path, accessToken, "{}");
        assertThat(response.statusCode()).as("%s as %s", route, who).isEqualTo(403);
        assertThat(response.body()).as("%s as %s", route, who).doesNotContain("\"code\"");
    }

    /**
     * Anything but the filter chain's own refusal. The chain's 401 always
     * carries WWW-Authenticate and its 403 never a problem code; every
     * refusal a handler makes carries a code.
     */
    private static void assertReachedTheHandler(Route route, HttpResponse<String> response) {
        assertThat(response.headers().firstValue("WWW-Authenticate")).as("%s: %s", route, response.body()).isEmpty();
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            assertThat(response.body()).as("%s", route).contains("\"code\"");
        }
    }

    /**
     * Every request carries the refresh route's CSRF header, so the matrix
     * tests authorization alone; CsrfHeaderFilter has tests of its own.
     */
    @SuppressWarnings("unchecked")
    private static HttpResponse<String> send(LocalHttp http, Route route, String path, String accessToken,
                                             Object body) {
        List<String> headers = new ArrayList<>(List.of(CsrfHeaderFilter.HEADER, CsrfHeaderFilter.VALUE));
        if (accessToken != null) {
            headers.add("Authorization");
            headers.add("Bearer " + accessToken);
        }
        String[] headerArray = headers.toArray(String[]::new);
        if (body instanceof List<?> parts) {
            return http.multipart(route.method(), path, (List<Part>) parts, headerArray);
        }
        String json = (String) body;
        return switch (route.method()) {
            case "GET" -> http.get(path, headerArray);
            case "DELETE" -> http.delete(path, headerArray);
            case "POST" -> json == null ? http.post(path, headerArray) : http.postJson(path, json, headerArray);
            case "PUT" -> http.multipart("PUT", path, List.of(), headerArray);
            default -> throw new IllegalArgumentException("No request builder for " + route.method());
        };
    }

    /** A new verified account on its own client IP, signed in, in the state the route needs. */
    private Actor actor(Setup setup) {
        LocalHttp http = new LocalHttp(port);
        String email = RegistrationApi.uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        SessionApi.Session session = new SessionApi(http).signIn(email, STRONG_PASSWORD);
        String token = session.accessToken();
        TwoFactorApi twoFactor = new TwoFactorApi(http);
        VerificationApi verification = new VerificationApi(http, mailpit);
        Map<String, String> extras = new HashMap<>();
        TestAuthenticator authenticator = null;
        switch (setup) {
            case SIGNED_IN -> {
            }
            case SETUP_PENDING -> {
                HttpResponse<String> started = twoFactor.setup(token, STRONG_PASSWORD);
                assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
                authenticator = new TestAuthenticator(JsonPath.read(started.body(), "$.secret"), clock);
            }
            case TWO_FACTOR_ON -> authenticator = twoFactor.enrol(token, STRONG_PASSWORD, clock).authenticator();
            case PHONE_CODE_SENT -> {
                VerificationApi.PendingPhone pending = verification.requestPhoneCode(email, token,
                        VerificationApi.uniquePhone(), 0);
                extras.put("verificationId", pending.verificationId());
                extras.put("code", pending.code());
            }
            case READY_FOR_SELFIE, SELFIE_STARTED -> {
                assertThat(verification.uploadProfilePhoto(token, TestImages.profilePhoto()).statusCode())
                        .isEqualTo(200);
                verification.verifyPhone(email, token);
                if (setup == Setup.SELFIE_STARTED) {
                    extras.put("challengeId", verification.challengeId(token));
                }
            }
            case CAN_VOUCH, VOUCHING -> {
                verification.reachSelfieMatch(email, token);
                LocalHttp friendHttp = new LocalHttp(port);
                String friendEmail = RegistrationApi.uniqueEmail();
                new RegistrationApi(friendHttp, mailpit).registerVerified(friendEmail);
                String friendId = new SessionApi(friendHttp).signIn(friendEmail, STRONG_PASSWORD).userId();
                extras.put("vouchTarget", friendId);
                if (setup == Setup.VOUCHING) {
                    HttpResponse<String> vouched = verification.vouch(token, friendId);
                    assertThat(vouched.statusCode()).as(vouched.body()).isEqualTo(201);
                    extras.put("vouchId", JsonPath.read(vouched.body(), "$.id"));
                }
            }
        }
        return new Actor(http, email, session, authenticator, extras);
    }

    /** A selfie waiting for review, written straight to the table: the admin routes only need it to exist. */
    private String pendingSelfieOf(String userId) {
        return jdbc.queryForObject("""
                insert into identity_verifications (user_id, tier, status, similarity_score, selfie_image_key,
                                                    purge_due_at)
                values (?::uuid, 'SELFIE_MATCH', 'PENDING', 0.700, ?, now() + interval '7 days')
                returning id::text""", String.class, userId, "selfies/" + UUID.randomUUID() + ".jpg");
    }

    private String signInWithSecondFactor(Actor actor) {
        String challengeId = TwoFactorApi.challengeId(new LoginApi(actor.http()).login(actor.email(), STRONG_PASSWORD));
        HttpResponse<String> response = new TwoFactorApi(actor.http())
                .secondFactor(challengeId, actor.authenticator().nextCode());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.session.accessToken");
    }

    /**
     * Everything the owner can see of their own account; reading it changes
     * none of it. A signed image link is re-signed on every read, so its
     * token and expiry are left out; which photo it points at is compared
     * through the database instead.
     */
    private Snapshot snapshot(Actor owner) {
        String bearer = "Bearer " + owner.accessToken();
        List<String> bodies = new ArrayList<>();
        for (String path : List.of("/api/v1/users/me", "/api/v1/auth/2fa", "/api/v1/auth/sessions",
                "/api/v1/verification", "/api/v1/vouches")) {
            HttpResponse<String> response = owner.http().get(path, "Authorization", bearer);
            assertThat(response.statusCode()).as("owner reading %s: %s", path, response.body()).isEqualTo(200);
            bodies.add(SIGNED_LINK.matcher(response.body()).replaceAll("\"profilePhoto\":{}"));
        }
        bodies.add(String.valueOf(jdbc.queryForObject("select profile_photo_key from users where id = ?::uuid",
                String.class, owner.userId())));
        return new Snapshot(bodies);
    }

    private Set<String> mappedRoutes(Predicate<Class<?>> handlerTypes) {
        Set<String> routes = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handlerTypes.test(handler.getBeanType())) {
                return;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (methods.isEmpty()) {
                    routes.add("ANY " + pattern);
                }
                for (RequestMethod method : methods) {
                    routes.add(method.name() + " " + pattern);
                }
            }
        });
        return routes;
    }

    /**
     * Ours, and shipped. Excluded: the servlet container's /error dispatch
     * (only reachable as an ERROR dispatch; a direct request needs a token
     * like any other path) and the test-only controllers in support.
     */
    private static boolean isProductionController(Class<?> type) {
        String pkg = type.getPackageName();
        return pkg.startsWith("com.tripmate.") && !pkg.startsWith("com.tripmate.support")
                && type != ProblemErrorController.class;
    }

    private static Set<String> uncovered(Set<String> mapped) {
        return difference(mapped, ROUTES.stream().map(Route::key).collect(Collectors.toSet()));
    }

    private static Set<String> difference(Set<String> from, Set<String> remove) {
        Set<String> result = new TreeSet<>(from);
        result.removeAll(remove);
        return result;
    }

    private static String expand(String template, Map<String, String> variables) {
        Matcher matcher = PATH_VARIABLE.matcher(template);
        StringBuilder path = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            assertThat(value).as("no value for {%s} in %s", matcher.group(1), template).isNotNull();
            matcher.appendReplacement(path, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(path);
        return path.toString();
    }

    enum Access {
        PUBLIC, SELF, OWNED, ADMIN
    }

    /** The owner's account state the route's own request needs to be one that can succeed. */
    enum Setup {
        SIGNED_IN, SETUP_PENDING, TWO_FACTOR_ON,
        /** A phone code sent; extras verificationId and code. */
        PHONE_CODE_SENT,
        /** A profile photo and a verified phone. */
        READY_FOR_SELFIE,
        /** Ready for a selfie, with a check started; extra challengeId. */
        SELFIE_STARTED,
        /** SELFIE_MATCH, with another account to vouch for; extra vouchTarget. */
        CAN_VOUCH,
        /** SELFIE_MATCH and vouching for another account; extras vouchTarget and vouchId. */
        VOUCHING
    }

    /**
     * @param path        as Spring MVC reports it, {variables} included
     * @param ownerStatus what the owner (or, for ADMIN, an admin with otp) gets
     * @param body        the caller's request body for their own account: a
     *                    JSON string, a list of multipart parts, or null for none
     * @param idVariable  OWNED only: the path variable naming the owner's resource, filled from the owner's extras
     */
    record Route(String method, String path, Access access, Setup setup, int ownerStatus,
                 Function<Actor, Object> body, String idVariable) {

        static final Function<Actor, Object> NO_BODY = actor -> null;

        static Route anonymous(String method, String path) {
            return new Route(method, path, Access.PUBLIC, null, 0, NO_BODY, null);
        }

        static Route self(String method, String path, Setup setup, int ownerStatus, Function<Actor, Object> body) {
            return new Route(method, path, Access.SELF, setup, ownerStatus, body, null);
        }

        static Route owned(String method, String path, Setup setup, String idVariable, int ownerStatus) {
            return new Route(method, path, Access.OWNED, setup, ownerStatus, NO_BODY, idVariable);
        }

        static Route admin(String method, String path, int ownerStatus, Function<Actor, Object> body) {
            return new Route(method, path, Access.ADMIN, null, ownerStatus, body, null);
        }

        String key() {
            return method + " " + path;
        }

        @Override
        public String toString() {
            return access + " " + key();
        }
    }

    /**
     * @param authenticator their phone; null when 2FA has not been set up
     * @param extras        IDs and codes their setup produced, by name
     */
    record Actor(LocalHttp http, String email, SessionApi.Session session, TestAuthenticator authenticator,
                 Map<String, String> extras) {

        String extra(String name) {
            String value = extras.get(name);
            assertThat(value).as("setup produced no %s", name).isNotNull();
            return value;
        }

        String accessToken() {
            return session.accessToken();
        }

        String userId() {
            return session.userId();
        }

        String sessionId() {
            return session.sessionId();
        }
    }

    record Snapshot(List<String> bodies) {
    }
}
