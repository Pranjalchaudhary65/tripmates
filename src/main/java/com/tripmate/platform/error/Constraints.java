package com.tripmate.platform.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/**
 * Tells which database constraint refused a write. Services use it where a
 * unique index is the final word on a race (two requests passing the same
 * check at once), and turn that one violation into the domain error it means.
 */
public final class Constraints {

    private Constraints() {
    }

    /**
     * Recognises the violation whether it came through Hibernate (its
     * ConstraintViolationException names the constraint) or through plain
     * JDBC, where only PostgreSQL's own message names it.
     */
    public static boolean violated(DataIntegrityViolationException e, String constraintName) {
        String quoted = "constraint \"" + constraintName + "\"";
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getMessage() != null && sql.getMessage().contains(quoted)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
