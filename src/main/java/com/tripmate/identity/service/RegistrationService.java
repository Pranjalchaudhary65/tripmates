package com.tripmate.identity.service;

import com.tripmate.identity.dto.RegistrationRequest;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.mail.ExistingAccountRegistrationEvent;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.platform.error.ApiProblemException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates accounts without revealing which addresses already have one.
 *
 * <p>Every check that can fail with an error (age, password rules, breach
 * corpus) runs before the address is looked up, so those errors are decided
 * by the request alone. After the lookup, every branch answers the same way:
 * a verification ID and a message to check the inbox. What differs is only
 * what reaches the inbox, which only the address owner can read:
 * <ul>
 *   <li>new address: account created, code sent</li>
 *   <li>unverified account: credentials replaced, old code superseded, new
 *       code sent - no one has proven ownership, so the latest attempt wins</li>
 *   <li>verified account: nothing changes, an "already registered" notice is
 *       sent, and the client gets a random decoy ID</li>
 * </ul>
 * The password is hashed before the branch on every path, so the expensive
 * step is not what distinguishes them in response time.
 */
@Service
public class RegistrationService {

    static final String EMAIL_UNIQUE_INDEX = "idx_users_email_active";

    private final UserRepository users;

    private final OtpService otpService;

    private final PasswordPolicy passwordPolicy;

    private final PasswordEncoder passwordEncoder;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public RegistrationService(UserRepository users, OtpService otpService, PasswordPolicy passwordPolicy,
                               PasswordEncoder passwordEncoder, ApplicationEventPublisher events,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.otpService = otpService;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Not transactional itself: the breach lookup is a network call and
     * Argon2id takes tens of milliseconds, neither of which should hold a
     * database connection or row lock.
     *
     * @return the verification ID to pair with the emailed code
     */
    public UUID register(RegistrationRequest request) {
        String email = request.email().trim();
        String fullName = request.fullName().trim();

        if (!AgePolicy.isAdult(request.dateOfBirth(), clock)) {
            throw new ApiProblemException(IdentityProblem.UNDERAGE);
        }
        passwordPolicy.enforce(request.password(), email, fullName);
        String passwordHash = passwordEncoder.encode(request.password());

        try {
            return transactions.execute(status -> registerLocked(new Registration(
                    email, passwordHash, fullName, request)));
        } catch (DataIntegrityViolationException e) {
            if (violates(e, EMAIL_UNIQUE_INDEX)) {
                // A concurrent request inserted this address between our lookup
                // and insert; answer as for any other already-taken address.
                return UUID.randomUUID();
            }
            throw e;
        }
    }

    private UUID registerLocked(Registration registration) {
        Instant now = clock.instant();
        Optional<User> existing = users.findLiveByEmailForUpdate(registration.email());

        if (existing.isEmpty()) {
            User user = users.saveAndFlush(User.register(registration.email(), registration.passwordHash(),
                    registration.fullName(), registration.request().dateOfBirth(),
                    registration.request().gender(), now));
            return otpService.issueEmailVerification(user);
        }

        User user = existing.get();
        if (!user.isPendingEmailVerification()) {
            events.publishEvent(new ExistingAccountRegistrationEvent(user.getEmail()));
            return UUID.randomUUID();
        }
        if (!otpService.canIssueEmailVerification(user.getId())) {
            return UUID.randomUUID();
        }
        user.replacePendingRegistration(registration.passwordHash(), registration.fullName(),
                registration.request().dateOfBirth(), registration.request().gender(), now);
        return otpService.issueEmailVerification(user);
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraint.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    private record Registration(String email, String passwordHash, String fullName, RegistrationRequest request) {

        @Override
        public String toString() {
            return "Registration[]";
        }
    }
}
