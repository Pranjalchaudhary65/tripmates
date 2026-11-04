package com.tripmate;

import com.tripmate.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class InfrastructureIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    void flywayAppliesBaselineMigrationOnStartup() {
        Boolean applied = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void baselineMigrationEnablesRequiredExtensions() {
        List<String> extensions = jdbc.queryForList(
                "SELECT extname FROM pg_extension WHERE extname IN ('pgcrypto', 'citext', 'postgis')",
                String.class);

        assertThat(extensions).containsExactlyInAnyOrder("pgcrypto", "citext", "postgis");
    }

    @Test
    void postgisSpatialFunctionsWork() {
        Double metres = jdbc.queryForObject(
                "SELECT ST_Distance('SRID=4326;POINT(77.2090 28.6139)'::geography, "
                        + "'SRID=4326;POINT(77.2100 28.6139)'::geography)",
                Double.class);

        assertThat(metres).isBetween(90.0, 110.0);
    }

    @Test
    void redisRespondsToPing() {
        try (var connection = redisConnectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }
}
