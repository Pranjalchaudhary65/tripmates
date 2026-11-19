package com.tripmate.identity.service;

import com.tripmate.identity.entity.LoginAttempt;
import com.tripmate.identity.entity.LoginFailureReason;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.mail.TwoFactorNoticeEvent;
import com.tripmate.identity.repository.LoginHistoryRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.platform.error.ApiProblemException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The second half of a login for an account with 2FA: a challenge from the
 * password step plus a TOTP or backup code buys the session.
 *
 * <p>Three limits apply to every code, in this order:
 * <ol>
 *   <li>the challenge's own attempt count, charged atomically in Redis -
 *       after {@code challengeMaxAttempts} codes it is gone and the password
 *       is needed again;</li>
 *   <li>a lockout counter of its own, {@code {password counter}:second-factor},
 *       charged before the code is checked, with the same doubling lock as
 *       passwords. A fresh challenge does not reset it, so knowing the
 *       password buys a handful of guesses per quarter hour, not five per
 *       login;</li>
 *   <li>the per-IP bucket on the route.</li>
 * </ol>
 *
 * <p>The code is checked and spent, the challenge deleted and the session
 * opened in one transaction under the user row lock. If two correct
 * submissions race, the challenge delete lets exactly one through; the other
 * rolls back, unspending its code.
 */
@Service
public class SecondFactorLoginService {

    static final String COUNTER_SUFFIX = ":second-factor";

    private final SecondFactorChallenges challenges;

    private final LoginLockout lockout;

    private final UserRepository users;

    private final LoginHistoryRepository history;

    private final SecondFactorVerifier verifier;

    private final LoginCompletion completion;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public SecondFactorLoginService(SecondFactorChallenges challenges, LoginLockout lockout, UserRepository users,
                                    LoginHistoryRepository history, SecondFactorVerifier verifier,
                                    LoginCompletion completion, ApplicationEventPublisher events,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.challenges = challenges;
        this.lockout = lockout;
        this.users = users;
        this.history = history;
        this.verifier = verifier;
        this.completion = completion;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws ApiProblemException {@link LoginProblem#LOGIN_CHALLENGE_EXPIRED}
     *         when the challenge is unknown, expired, used up or already
     *         completed; {@link LoginProblem#INVALID_SECOND_FACTOR} for every
     *         refused code
     * @throws com.tripmate.platform.error.RetryLaterException when Redis is unreachable (503)
     */
    public LoginResult complete(String challengeId, String code, LoginClient client) {
        Instant now = clock.instant();
        SecondFactorChallenges.Pending pending = challenges.charge(challengeId, now)
                .orElseThrow(() -> new ApiProblemException(LoginProblem.LOGIN_CHALLENGE_EXPIRED));
        String counterKey = pending.counterKey() + COUNTER_SUFFIX;
        LoginLockout.Attempt charged = lockout.charge(counterKey, now);

        Outcome outcome = transactions.execute(status -> {
            User user = users.findByIdForUpdate(pending.userId()).orElseThrow();
            if (!user.isActive() || !user.isEmailVerified()) {
                refuse(user, client, LoginFailureReason.ACCOUNT_DISABLED, now);
                return Outcome.refused();
            }
            if (!charged.isAllowed()) {
                refuse(user, client, LoginFailureReason.LOCKED, now);
                return Outcome.refused();
            }
            Optional<SecondFactorVerifier.Verified> verified = verifier.verify(user, code, now);
            if (verified.isEmpty()) {
                refuse(user, client, LoginFailureReason.BAD_SECOND_FACTOR, now);
                return Outcome.refused();
            }
            if (!challenges.consume(challengeId)) {
                status.setRollbackOnly();
                return Outcome.expired();
            }
            if (verified.get().method() == SecondFactorVerifier.Method.BACKUP_CODE) {
                events.publishEvent(new TwoFactorNoticeEvent(user.getEmail(), user.getFullName(),
                        TwoFactorNoticeEvent.Kind.BACKUP_CODE_USED, now, client.address().hostAddress(),
                        verified.get().backupCodesLeft()));
            }
            return Outcome.signedIn(completion.complete(user, user.getEmail(), client, true));
        });

        return switch (outcome.kind()) {
            case REFUSED -> throw new ApiProblemException(LoginProblem.INVALID_SECOND_FACTOR);
            case EXPIRED -> throw new ApiProblemException(LoginProblem.LOGIN_CHALLENGE_EXPIRED);
            case SIGNED_IN -> {
                lockout.reset(counterKey);
                yield outcome.result();
            }
        };
    }

    /** Written in the same transaction, which commits: a refused code must stay counted and recorded. */
    private void refuse(User user, LoginClient client, LoginFailureReason reason, Instant now) {
        String deviceHash = client.deviceId() == null ? null : DeviceIds.hash(client.deviceId());
        history.save(LoginAttempt.failed(user.getId(), user.getEmail(), client.address().address(),
                client.userAgent(), deviceHash, reason, now));
    }

    private record Outcome(Kind kind, LoginResult result) {

        static Outcome refused() {
            return new Outcome(Kind.REFUSED, null);
        }

        static Outcome expired() {
            return new Outcome(Kind.EXPIRED, null);
        }

        static Outcome signedIn(LoginResult result) {
            return new Outcome(Kind.SIGNED_IN, result);
        }

        enum Kind {
            REFUSED, EXPIRED, SIGNED_IN
        }
    }
}
