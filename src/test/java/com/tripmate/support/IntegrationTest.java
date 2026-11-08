package com.tripmate.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application on a random port, against real Postgres and Redis
 * containers. Every integration test uses this one annotation so they all
 * resolve to the same cached Spring context - a test class with a slightly
 * different setup would start its own context and a second pair of
 * containers.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StubBreachedPasswords.class,
        ProblemScenarioController.class, ProblemScenarioSecurity.class})
public @interface IntegrationTest {
}
