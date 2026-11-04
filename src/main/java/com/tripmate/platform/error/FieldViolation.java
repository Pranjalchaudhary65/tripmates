package com.tripmate.platform.error;

/**
 * One failed validation rule, shaped for a form: {@code field} is the input
 * name the client sent (null for a rule on the whole object), {@code message}
 * the constraint's message. The rejected value is deliberately left out - it
 * may be a password or other secret the client just typed.
 */
public record FieldViolation(String field, String message) {
}
