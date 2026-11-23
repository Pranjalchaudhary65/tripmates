package com.tripmate.platform.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tells which database constraint refused a write. Services use it where a
 * unique index is the final word on a race (two requests passing the same
 * check at once), and turn that one violation into the domain error it means.
 */
public final class Constraints {

    private Constraints() {
    }

    public static boolean violated(DataIntegrityViolationException e, String constraintName) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
