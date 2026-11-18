package com.tripmate.identity;

import com.redis.testcontainers.RedisContainer;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.DockerClientFactory;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Redis stops answering mid-session. The container is paused rather than
 * stopped, so connections hang the way they do in a real network partition
 * and every call runs into the client timeout, then it is resumed so the
 * rest of the suite keeps its Redis.
 */
@IntegrationTest
class SessionStoreOutageIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private RedisContainer redisContainer;

    @Test
    void withRedisUnreachableSessionsFailClosedAndSurviveTheOutage() {
        LocalHttp http = new LocalHttp(port);
        SessionApi api = new SessionApi(http);
        String email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        SessionApi.Session session = api.signIn(email, STRONG_PASSWORD);

        HttpResponse<String> me;
        HttpResponse<String> refresh;
        HttpResponse<String> logout;
        pause();
        try {
            me = api.me(session.accessToken());
            refresh = api.refresh(session.refreshToken());
            logout = api.logout(session.accessToken());
        } finally {
            unpause();
        }

        for (HttpResponse<String> response : List.of(me, refresh, logout)) {
            assertThat(response.statusCode()).as(response.uri().toString()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).hasValue("30");
            assertThat(SessionApi.code(response)).isEqualTo("temporarily_unavailable");
            // A 503 must never look like a sign-out to the browser.
            assertThat(SessionApi.refreshSetCookie(response)).isEmpty();
        }

        // Nothing was consumed or revoked while Redis was away: the same cookie still works.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .until(() -> api.me(session.accessToken()).statusCode() == 200);
        HttpResponse<String> afterwards = api.refresh(session.refreshToken());
        assertThat(afterwards.statusCode()).isEqualTo(200);
        assertThat(api.me(SessionApi.accessTokenOfRefresh(afterwards)).statusCode()).isEqualTo(200);
    }

    private void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(redisContainer.getContainerId()).exec();
    }

    private void unpause() {
        DockerClientFactory.instance().client().unpauseContainerCmd(redisContainer.getContainerId()).exec();
    }
}
