package com.tripmate.identity.service;

import com.tripmate.identity.entity.SessionRevocationReason;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.platform.audit.AuditEvent;
import com.tripmate.platform.audit.AuditLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Taking an account out of use, and back. No endpoint reaches this directly:
 * the moderation module calls it when reports cross the auto-suspend
 * threshold or a moderator decides.
 *
 * <p>Suspending, in one transaction under the account's row lock: the status
 * changes, every session ends at once (an access token dies with its
 * session), and everyone actively vouching for the account is penalised
 * (DESIGN.md, Vouching). Suspending an account that is already out of use
 * does nothing, so the penalty is applied once however many reports race.
 */
@Service
public class AccountModeration {

    private final UserRepository users;

    private final SessionService sessions;

    private final VouchService vouches;

    private final AuditLog audit;

    private final Clock clock;

    public AccountModeration(UserRepository users, SessionService sessions, VouchService vouches, AuditLog audit,
                             Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.vouches = vouches;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Locks the account's row for the rest of the caller's transaction, so
     * decisions about it (counting reports, suspending) happen one at a time.
     *
     * @return false when there is no such account
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(UUID userId) {
        return users.findByIdForUpdate(userId).isPresent();
    }

    /**
     * @param actorId the moderator, or null when the report threshold did it
     * @return true when the account was active and is now suspended
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean suspend(UUID userId, UUID actorId) {
        Instant now = clock.instant();
        User user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null || !user.suspend(now)) {
            return false;
        }
        users.saveAndFlush(user);
        int penalised = vouches.penalizeVouchersOf(userId);
        audit.record(actorId, AuditEvent.ACCOUNT_SUSPENDED, "USER", userId,
                Map.of("by", actorId == null ? "REPORT_THRESHOLD" : "MODERATOR", "vouchersPenalised", penalised));
        sessions.revokeEverySession(userId, SessionRevocationReason.ADMIN);
        return true;
    }

    /** @return true when the account was suspended and is active again */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reinstate(UUID userId, UUID actorId) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null || !user.reinstate(clock.instant())) {
            return false;
        }
        users.saveAndFlush(user);
        audit.record(actorId, AuditEvent.ACCOUNT_REINSTATED, "USER", userId);
        return true;
    }

    /** Whether the account can be used right now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isActive(UUID userId) {
        return users.findById(userId).map(User::isActive).orElse(false);
    }
}
