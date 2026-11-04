package com.tripmate.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres (with PostGIS) and Redis for integration tests, using the
 * same images as docker-compose.yml so tests exercise what dev and prod run.
 * {@code @ServiceConnection} hands each container's host, port and
 * credentials straight to the auto-configured DataSource and Redis client,
 * overriding application.yml's datasource and Redis settings.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    private static final DockerImageName REDIS = DockerImageName.parse("redis:7-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGIS);
    }

    @Bean
    @ServiceConnection
    RedisContainer redisContainer() {
        return new RedisContainer(REDIS);
    }
}
