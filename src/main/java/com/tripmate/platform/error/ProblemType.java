package com.tripmate.platform.error;

import org.springframework.http.HttpStatus;

/**
 * A domain error a client is expected to handle. Implemented by enums, so
 * every detail a client can receive is a compile-time constant in the
 * codebase, the same guarantee {@link ApiProblems} gives for status-only
 * errors.
 */
public interface ProblemType {

    HttpStatus status();

    /** Stable machine-readable identifier, e.g. {@code underage}; clients branch on this, not on the text. */
    String code();

    String detail();

    /** The request field the error belongs to, or null when it concerns the request as a whole. */
    String field();
}
