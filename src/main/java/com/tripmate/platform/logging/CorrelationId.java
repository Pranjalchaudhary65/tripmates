package com.tripmate.platform.logging;

import org.slf4j.MDC;

import java.util.regex.Pattern;

/**
 * Names and validation rules for the per-request correlation ID, shared by
 * the filter that assigns it and the error responses that report it.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";

    public static final String MDC_KEY = "correlationId";

    static final String REQUEST_ATTRIBUTE = CorrelationId.class.getName();

    /**
     * Inbound IDs are attacker-controlled and end up in every log line, so
     * only a short, plain token is accepted. Anything else - CR/LF for log
     * forging, a megabyte of padding, JSON fragments - is discarded and a
     * fresh ID generated instead.
     */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    static boolean isAcceptable(String candidate) {
        return candidate != null && ACCEPTED.matcher(candidate).matches();
    }

    /** The correlation ID of the request on the current thread, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
