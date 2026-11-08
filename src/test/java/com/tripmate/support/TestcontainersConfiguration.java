package com.tripmate.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres (with PostGIS), Redis and Mailpit for integration tests,
 * using the same images as docker-compose.yml so tests exercise what dev and
 * prod run. {@code @ServiceConnection} hands the Postgres and Redis
 * containers' host, port and credentials straight to the auto-configured
 * DataSource and Redis client. Mailpit has no service connection, so its
 * SMTP host and port are registered as properties instead; emails really go
 * over SMTP and are read back through Mailpit's API.
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

    @Bean
    MailpitContainer mailpitContainer() {
        return new MailpitContainer();
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(MailpitContainer mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }

    @Bean
    MailpitClient mailpitClient(MailpitContainer mailpit) {
        return new MailpitClient(mailpit.apiBaseUrl());
    }
}
