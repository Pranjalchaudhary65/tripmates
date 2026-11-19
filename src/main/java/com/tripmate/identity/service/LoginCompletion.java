package com.tripmate.identity.service;

import com.tripmate.identity.entity.LoginAttempt;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.mail.NewDeviceLoginEvent;
import com.tripmate.identity.repository.LoginHistoryRepository;
import com.tripmate.identity.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * The last step of every successful login, with or without a second factor:
 * record it, alert on a new device, open the session.
 *
 * <p>Joins the caller's transaction when there is one (the second-factor
 * step, which has just spent a code under the same user lock), or starts its
 * own (a password-only login).
 */
@Component
public class LoginCompletion {

    private final UserRepository users;

    private final LoginHistoryRepository history;

    private final SessionService sessions;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public LoginCompletion(UserRepository users, LoginHistoryRepository history, SessionService sessions,
                           ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.users = users;
        this.history = history;
        this.sessions = sessions;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Keeps the browser's existing device ID even when it is new to this
     * account (another account signed in on the same browser before): the ID
     * names the browser, not the person.
     *
     * <p>The user row is locked so two concurrent first sign-ins from the
     * same new browser record one "new device" between them, not two. The
     * session is opened in the same transaction, from the locked copy of the
     * user, so its role and tier are the ones committed at that moment.
     *
     * @param secondFactorVerified whether this login passed TOTP or a backup
     *                             code; becomes the session's amr for its whole life
     */
    public LoginResult complete(User user, String email, LoginClient client, boolean secondFactorVerified) {
        String deviceId = client.deviceId() != null ? client.deviceId() : DeviceIds.generate();
        String deviceHash = DeviceIds.hash(deviceId);
        Instant now = clock.instant();

        SessionTokens session = transactions.execute(status -> {
            User locked = users.findByIdForUpdate(user.getId()).orElseThrow();
            boolean seenBefore = history.existsByUserIdAndDeviceIdHashAndSuccessTrue(user.getId(), deviceHash);
            boolean firstSignIn = !history.existsByUserIdAndSuccessTrue(user.getId());
            history.save(LoginAttempt.succeeded(user.getId(), email, client.address().address(),
                    client.userAgent(), deviceHash, now));
            // The very first sign-in is from a new device by definition; alerting
            // on it would only teach people to ignore the alert.
            if (!seenBefore && !firstSignIn) {
                events.publishEvent(new NewDeviceLoginEvent(locked.getEmail(), locked.getFullName(), now,
                        client.address().hostAddress()));
            }
            return sessions.open(locked, client, now, secondFactorVerified);
        });
        return LoginResult.signedIn(deviceId, session);
    }
}
