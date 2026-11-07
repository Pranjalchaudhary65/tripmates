package com.tripmate.platform.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CorsPropertiesTest {

    @Test
    void acceptsExactHttpsOriginsAndLocalhostOverHttp() {
        CorsProperties properties = new CorsProperties(List.of(
                "http://localhost:5173", " https://app.tripmates.example ", "http://127.0.0.1:4173"));

        assertThat(properties.allowedOrigins()).containsExactly(
                "http://localhost:5173", "https://app.tripmates.example", "http://127.0.0.1:4173");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "*",
            "https://*.tripmates.example",
            "",
            "null",
            "localhost:5173",
            "ftp://files.tripmates.example",
            "http://app.tripmates.example",
            "https://app.tripmates.example/",
            "https://app.tripmates.example/app",
            "https://app.tripmates.example?x=1",
            "https://user@app.tripmates.example",
            "http://[not-an-origin"
    })
    void rejectsAnythingButAnExactOrigin(String origin) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CorsProperties(List.of("http://localhost:5173", origin)))
                .withMessageContaining("tripmate.cors.allowed-origins");
    }

    @Test
    void rejectsAnEmptyList() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CorsProperties(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new CorsProperties(null));
    }
}
