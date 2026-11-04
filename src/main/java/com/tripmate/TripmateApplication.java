package com.tripmate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * UserDetailsServiceAutoConfiguration is excluded because it creates an
 * in-memory "user" account with a random password and prints that password
 * to the log on every startup. No login mechanism here uses it.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class TripmateApplication {

	public static void main(String[] args) {
		SpringApplication.run(TripmateApplication.class, args);
	}

}
