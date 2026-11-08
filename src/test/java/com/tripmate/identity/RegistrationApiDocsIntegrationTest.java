package com.tripmate.identity;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class RegistrationApiDocsIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void everyRegistrationRouteIsDocumentedWithProblemJsonErrors() {
        String spec = new LocalHttp(port).get("/v3/api-docs").body();
        DocumentContext doc = JsonPath.parse(spec);

        for (String path : new String[] {RegistrationApi.REGISTER, RegistrationApi.VERIFY_EMAIL, RegistrationApi.RESEND}) {
            Map<String, Object> badRequestContent = doc.read("$.paths['" + path + "'].post.responses['400'].content");
            assertThat(badRequestContent).as(path).containsKey("application/problem+json");
        }
        assertThat(doc.<Map<String, Object>>read("$.paths['" + RegistrationApi.REGISTER + "'].post.responses"))
                .containsKey("202");
    }
}
