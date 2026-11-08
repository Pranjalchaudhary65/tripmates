package com.tripmate.identity.service;

import com.tripmate.identity.password.BreachedPasswordChecker;
import com.tripmate.identity.password.BreachedPasswordChecker.BreachStatus;
import com.tripmate.platform.error.ApiProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Password rules beyond length (which the request DTO enforces), following
 * NIST SP 800-63B: no composition rules ("one uppercase, one symbol"), which
 * push people toward predictable patterns like "Password1!", but reject
 * passwords built from the user's own details and passwords known from
 * breaches.
 *
 * <p>When the breach corpus cannot be reached, registration proceeds; see
 * {@link #enforce}.
 */
@Component
public class PasswordPolicy {

    /** Name parts or email local parts shorter than this match too much by accident ("al" in "totally"). */
    static final int MIN_PERSONAL_TOKEN_LENGTH = 4;

    private static final Pattern NAME_SEPARATORS = Pattern.compile("[\\s.\\-_'+]+");

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicy.class);

    private final BreachedPasswordChecker breachedPasswords;

    public PasswordPolicy(BreachedPasswordChecker breachedPasswords) {
        this.breachedPasswords = breachedPasswords;
    }

    /**
     * Fails open on an unreachable breach corpus: the check is one layer on
     * top of the length rule, Argon2id and rate limiting, and failing closed
     * would turn any HIBP outage, or a slow network path to it, into a
     * complete registration outage for every user.
     */
    public void enforce(String password, String email, String fullName) {
        if (containsPersonalInfo(password, email, fullName)) {
            throw new ApiProblemException(IdentityProblem.PASSWORD_CONTAINS_PERSONAL_INFO);
        }
        BreachStatus status = breachedPasswords.check(password);
        if (status == BreachStatus.BREACHED) {
            throw new ApiProblemException(IdentityProblem.PASSWORD_BREACHED);
        }
        if (status == BreachStatus.UNAVAILABLE) {
            log.warn("Breached-password check unavailable; accepted a password without it");
        }
    }

    static boolean containsPersonalInfo(String password, String email, String fullName) {
        String haystack = password.toLowerCase(Locale.ROOT);
        String localPart = email.substring(0, Math.max(email.indexOf('@'), 0)).toLowerCase(Locale.ROOT);
        if (localPart.length() >= MIN_PERSONAL_TOKEN_LENGTH && haystack.contains(localPart)) {
            return true;
        }
        for (String part : NAME_SEPARATORS.split(fullName.toLowerCase(Locale.ROOT))) {
            if (part.length() >= MIN_PERSONAL_TOKEN_LENGTH && haystack.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
