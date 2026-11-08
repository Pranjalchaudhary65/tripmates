package com.tripmate.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** The same Mailpit image docker-compose.yml runs for local dev. */
public class MailpitContainer extends GenericContainer<MailpitContainer> {

    private static final DockerImageName IMAGE = DockerImageName.parse("axllent/mailpit:v1.32.0");

    private static final int SMTP_PORT = 1025;

    private static final int HTTP_PORT = 8025;

    public MailpitContainer() {
        super(IMAGE);
        withExposedPorts(SMTP_PORT, HTTP_PORT);
        // Same reason as in docker-compose.yml: without it every SMTP
        // connection stalls on a reverse DNS lookup of the client.
        withEnv("MP_SMTP_DISABLE_RDNS", "true");
        waitingFor(Wait.forHttp("/livez").forPort(HTTP_PORT));
    }

    public int smtpPort() {
        return getMappedPort(SMTP_PORT);
    }

    public String apiBaseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }
}
