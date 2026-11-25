package com.tripmate.identity.service;

import com.tripmate.identity.entity.RefreshTokenFamily;
import com.tripmate.identity.entity.SessionRevocationReason;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.RefreshTokenFamilyRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.AccessToken;
import com.tripmate.identity.token.AccessTokenIssuer;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.web.ClientAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Sessions: opening one at login, rotating its refresh token, ending one or
 * all of them, and listing them.
 *
 * <p>A refresh has to pass Postgres (the family exists, is not revoked and
 * has not expired, and its user is still active) and then Redis (the token
 * is the family's current one). Postgres is the durable record of whether a
 * session is alive; Redis knows which token is current. Either one saying
 * no is enough to refuse.
 *
 * <p>Ending sessions revokes in Postgres and deletes the Redis keys in one
 * transaction. The Redis delete runs before the commit: if Redis cannot be
 * reached, the revocation rolls back and the caller gets a 503, never a
 * success that did not happen.
 */
@Service
@EnableConfigurationProperties(SessionProperties.class)
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final RefreshTokenFamilyRepository families;

    private final UserRepository users;

    private final RefreshTokenStore store;

    private final AccessTokenIssuer accessTokens;

    private final SessionProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public SessionService(RefreshTokenFamilyRepository families, UserRepository users, RefreshTokenStore store,
                          AccessTokenIssuer accessTokens, SessionProperties properties,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.families = families;
        this.users = users;
        this.store = store;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Opens a new family for a login that has just succeeded. Runs inside
     * the login's own transaction, so a Redis failure here rolls the whole
     * success back, login_history row included, and the login answers 503.
     *
     * @param secondFactorVerified recorded on the family, so every access
     *                             token it ever mints, after any number of
     *                             refreshes, carries the same amr
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionTokens open(User user, LoginClient client, Instant now, boolean secondFactorVerified) {
        Instant expiresAt = now.plus(properties.lifetime());
        RefreshTokenFamily family = families.save(RefreshTokenFamily.open(user.getId(),
                DeviceLabels.describe(client.userAgent()), client.address().address(), client.userAgent(),
                now, expiresAt, secondFactorVerified));
        String refreshToken = RefreshTokens.generate(family.getId());
        store.create(family.getId(), RefreshTokens.hash(refreshToken), properties.lifetime());
        return tokens(user, family, refreshToken, now);
    }

    /**
     * @throws ApiProblemException {@link SessionProblem#INVALID_REFRESH_TOKEN}
     *         for every way the token can be wrong, including reuse
     * @throws com.tripmate.platform.error.RetryLaterException when Redis is
     *         unreachable (503); the presented token is left untouched
     */
    public SessionTokens refresh(String presentedToken, ClientAddress client) {
        UUID familyId = RefreshTokens.familyOf(presentedToken).orElseThrow(SessionService::invalid);
        Instant now = clock.instant();
        RefreshTokenFamily family = families.findById(familyId)
                .filter(candidate -> candidate.isLiveAt(now))
                .orElseThrow(SessionService::invalid);
        User user = users.findById(family.getUserId())
                .filter(User::isActive)
                .orElseThrow(SessionService::invalid);

        String nextToken = RefreshTokens.generate(familyId);
        RefreshTokenStore.Rotation rotation = store.rotate(familyId, RefreshTokens.hash(presentedToken),
                RefreshTokens.hash(nextToken), now, properties.reuseGrace());

        return switch (rotation) {
            case ROTATED -> {
                Integer touched = transactions.execute(status -> families.touch(familyId, now, client.address()));
                if (touched == null || touched == 0) {
                    // Revoked between the read above and now: the new token must not survive either.
                    store.delete(List.of(familyId));
                    throw invalid();
                }
                yield tokens(user, family, nextToken, now);
            }
            case RACED_WITHIN_GRACE -> tokens(user, family, null, now);
            case REUSED -> {
                log.warn("Refresh token reuse detected, revoking session {} of user {}", familyId, user.getId());
                revoke(user.getId(), List.of(familyId), SessionRevocationReason.REUSE_DETECTED);
                throw invalid();
            }
            case UNKNOWN_FAMILY -> throw invalid();
        };
    }

    /** Ends the caller's own session, the one their access token was minted from. */
    public void logout(SessionPrincipal caller) {
        revoke(caller.userId(), List.of(caller.sessionId()), SessionRevocationReason.LOGOUT);
    }

    /** Ends every session of the caller, the current one included. */
    public void revokeAll(SessionPrincipal caller) {
        transactions.executeWithoutResult(status -> {
            List<UUID> live = families.findLiveIdsByUserId(caller.userId());
            revokeInTransaction(caller.userId(), live, SessionRevocationReason.REVOKE_ALL_DEVICES);
        });
    }

    /**
     * Ends every session of the user except {@code keep}, inside the
     * caller's transaction: a change to how the account signs in must not
     * leave sessions opened under the old rules running. The bulk update
     * clears the persistence context, so callers make their entity changes
     * before calling this.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeOthers(UUID userId, UUID keep, SessionRevocationReason reason) {
        List<UUID> others = families.findLiveIdsByUserId(userId).stream()
                .filter(id -> !id.equals(keep))
                .toList();
        revokeInTransaction(userId, others, reason);
    }

    /**
     * Ends every session of the user, inside the caller's transaction: used
     * when moderation takes an account out of use. As with
     * {@link #revokeOthers}, make entity changes before calling this.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeEverySession(UUID userId, SessionRevocationReason reason) {
        revokeInTransaction(userId, families.findLiveIdsByUserId(userId), reason);
    }

    /** The caller's live sessions, most recently used first. Only ever the caller's own. */
    public List<RefreshTokenFamily> list(SessionPrincipal caller) {
        return families.findTop100ByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(
                caller.userId(), clock.instant());
    }

    /** Whether the session an access token belongs to has not been ended since it was issued. */
    public boolean isLive(UUID sessionId) {
        return store.isLive(sessionId);
    }

    private void revoke(UUID userId, Collection<UUID> familyIds, SessionRevocationReason reason) {
        transactions.executeWithoutResult(status -> revokeInTransaction(userId, familyIds, reason));
    }

    private void revokeInTransaction(UUID userId, Collection<UUID> familyIds, SessionRevocationReason reason) {
        if (familyIds.isEmpty()) {
            return;
        }
        families.revoke(userId, familyIds, reason, clock.instant());
        store.delete(familyIds);
    }

    private SessionTokens tokens(User user, RefreshTokenFamily family, String refreshToken, Instant now) {
        AccessToken accessToken = accessTokens.issue(user.getId(), user.getRole(), family.getId(),
                family.getExpiresAt(), family.isSecondFactorVerified());
        return new SessionTokens(user.getId(), user.getRole(), user.getIdVerificationTier(), accessToken,
                refreshToken, Duration.between(now, family.getExpiresAt()));
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(SessionProblem.INVALID_REFRESH_TOKEN);
    }
}
