package com.tripmate.platform.error;

/**
 * Thrown by a service to answer with a specific {@link ProblemType}. The
 * response is built from the type alone; the exception carries no free-text
 * message that could end up in a response. Stack traces are not captured:
 * this is an expected outcome, not a fault.
 */
public class ApiProblemException extends RuntimeException {

    private final transient ProblemType type;

    public ApiProblemException(ProblemType type) {
        super(type.code(), null, false, false);
        this.type = type;
    }

    public ProblemType type() {
        return type;
    }
}
