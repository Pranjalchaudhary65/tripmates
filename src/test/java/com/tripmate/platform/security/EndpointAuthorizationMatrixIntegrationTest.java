package com.tripmate.platform.security;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.LoginApi;
import com.tripmate.identity.RegistrationApi;
import com.tripmate.identity.SessionApi;
import com.tripmate.identity.TwoFactorApi;
import com.tripmate.platform.error.ProblemErrorController;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import com.tripmate.support.ProblemScenarioController;
import com.tripmate.support.TestAuthenticator;
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
 *       account, 2FA state and sessions are byte-for-byte unchanged after it.
 *       The owner gets the success status.</li>
 *   <li>{@link Access#ADMIN}: anonymous gets 401; a signed-in user who is not
 *       an admin gets 403, as does a 2FA user who is not an admin and an admin
 *       whose session skipped the second factor; an admin signed in with a
 *       second factor gets the success status.</li>
 * </ul>
 *
 * <p>A resource addressed by an ID that belongs to someone else answers 404,
 * exactly as an ID that does not exist (DESIGN.md, "Authorization tests");
 * the first such route adds its rule here.
 *
 * <p>{@link #everyMappedRouteHasExactlyOneEntry()} reads the routes from
 * Spring MVC itself, so a new controller method without an entry below fails
 * the build instead of shipping unchecked.
 */
@IntegrationTest
class EndpointAuthorizationMatrixIntegrationTest {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(\\w+)}");

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

            Route.admin("GET", "/api/v1/admin/users/{userId}", 200));

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

    private void checkPublic(Route route) {
        HttpResponse<String> plain = send(anonymous, route, route.path(), null, "{}");
        HttpResponse<String> withForgedToken = send(anonymous, route, route.path(), FORGED_TOKEN, "{}");

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

    private void checkAdmin(Route route) {
        Actor user = actor(Setup.SIGNED_IN);
        String path = expand(route.path(), Map.of("userId", user.userId()));
        assertRefusedAsAnonymous(route, path);

        assertForbidden(route, user.http(), path, user.accessToken(), "a user");
        Actor userWithOtp = actor(Setup.TWO_FACTOR_ON);
        assertForbidden(route, userWithOtp.http(), path, signInWithSecondFactor(userWithOtp), "a user with otp in amr");

        Actor admin = actor(Setup.TWO_FACTOR_ON);
        jdbc.update("update users set role = 'ADMIN' where id = ?::uuid", admin.userId());
        String adminWithoutOtp = SessionApi.accessTokenOfRefresh(
                new SessionApi(admin.http()).refresh(admin.session().refreshToken()));
        assertForbidden(route, admin.http(), path, adminWithoutOtp, "an admin without otp in amr");

        HttpResponse<String> asAdmin = send(admin.http(), route, path, signInWithSecondFactor(admin), null);
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
    private static HttpResponse<String> send(LocalHttp http, Route route, String path, String accessToken,
                                             String body) {
        List<String> headers = new ArrayList<>(List.of(CsrfHeaderFilter.HEADER, CsrfHeaderFilter.VALUE));
        if (accessToken != null) {
            headers.add("Authorization");
            headers.add("Bearer " + accessToken);
        }
        String[] headerArray = headers.toArray(String[]::new);
        return switch (route.method()) {
            case "GET" -> http.get(path, headerArray);
            case "POST" -> body == null ? http.post(path, headerArray) : http.postJson(path, body, headerArray);
            default -> throw new IllegalArgumentException("No request builder for " + route.method());
        };
    }

    /** A new verified account on its own client IP, signed in, with its 2FA in the state the route needs. */
    private Actor actor(Setup setup) {
        LocalHttp http = new LocalHttp(port);
        String email = RegistrationApi.uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        SessionApi.Session session = new SessionApi(http).signIn(email, STRONG_PASSWORD);
        TwoFactorApi twoFactor = new TwoFactorApi(http);
        TestAuthenticator authenticator = switch (setup) {
            case SIGNED_IN -> null;
            case SETUP_PENDING -> {
                HttpResponse<String> started = twoFactor.setup(session.accessToken(), STRONG_PASSWORD);
                assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
                yield new TestAuthenticator(JsonPath.read(started.body(), "$.secret"), clock);
            }
            case TWO_FACTOR_ON -> twoFactor.enrol(session.accessToken(), STRONG_PASSWORD, clock).authenticator();
        };
        return new Actor(http, email, session, authenticator);
    }

    private String signInWithSecondFactor(Actor actor) {
        String challengeId = TwoFactorApi.challengeId(new LoginApi(actor.http()).login(actor.email(), STRONG_PASSWORD));
        HttpResponse<String> response = new TwoFactorApi(actor.http())
                .secondFactor(challengeId, actor.authenticator().nextCode());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.session.accessToken");
    }

    /** Everything the owner can see of their own account; reading it changes none of it. */
    private Snapshot snapshot(Actor owner) {
        String bearer = "Bearer " + owner.accessToken();
        List<String> bodies = new ArrayList<>();
        for (String path : List.of("/api/v1/users/me", "/api/v1/auth/2fa", "/api/v1/auth/sessions")) {
            HttpResponse<String> response = owner.http().get(path, "Authorization", bearer);
            assertThat(response.statusCode()).as("owner reading %s: %s", path, response.body()).isEqualTo(200);
            bodies.add(response.body());
        }
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
        PUBLIC, SELF, ADMIN
    }

    /** The owner's account state the route's own request needs to be one that can succeed. */
    enum Setup {
        SIGNED_IN, SETUP_PENDING, TWO_FACTOR_ON
    }

    /**
     * @param path        as Spring MVC reports it, {variables} included
     * @param ownerStatus what the owner (or, for ADMIN, an admin with otp) gets
     * @param body        the owner's or non-owner's request body for their own account; null sends none
     */
    record Route(String method, String path, Access access, Setup setup, int ownerStatus,
                 Function<Actor, String> body) {

        static final Function<Actor, String> NO_BODY = actor -> null;

        static Route anonymous(String method, String path) {
            return new Route(method, path, Access.PUBLIC, null, 0, NO_BODY);
        }

        static Route self(String method, String path, Setup setup, int ownerStatus, Function<Actor, String> body) {
            return new Route(method, path, Access.SELF, setup, ownerStatus, body);
        }

        static Route admin(String method, String path, int ownerStatus) {
            return new Route(method, path, Access.ADMIN, null, ownerStatus, NO_BODY);
        }

        String key() {
            return method + " " + path;
        }

        @Override
        public String toString() {
            return access + " " + key();
        }
    }

    /** @param authenticator their phone; null when 2FA has not been set up */
    record Actor(LocalHttp http, String email, SessionApi.Session session, TestAuthenticator authenticator) {

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
