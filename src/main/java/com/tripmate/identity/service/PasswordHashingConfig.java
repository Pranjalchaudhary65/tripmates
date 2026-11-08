package com.tripmate.identity.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id at OWASP's first recommended setting: 19 MiB of memory, 2
 * iterations, 1 lane, 16-byte salt, 32-byte hash. Memory hardness is the
 * point: a GPU has thousands of cores but not thousands of 19 MiB scratch
 * areas, so offline cracking of a leaked hash cannot parallelise cheaply.
 *
 * <p>The parameters are written into every hash ({@code $argon2id$v=19$m=19456,t=2,p=1$...}),
 * so raising them later only affects new hashes, and old ones still verify.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordHashingConfig {

    static final int SALT_LENGTH_BYTES = 16;
    static final int HASH_LENGTH_BYTES = 32;
    static final int PARALLELISM = 1;
    static final int MEMORY_KIB = 19 * 1024;
    static final int ITERATIONS = 2;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }
}
