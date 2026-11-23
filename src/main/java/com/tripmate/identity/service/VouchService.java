package com.tripmate.identity.service;

import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.entity.Vouch;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.repository.VouchRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Vouching: a SELFIE_MATCH account puts a little of its own standing behind
 * someone else (DESIGN.md, Trust Score Engine, Vouching).
 *
 * <p>Scarcity is what makes a vouch worth anything, so only a verified
 * account can give one, and only {@code maxActiveVouches} at a time. The cap
 * is counted under the voucher's user row lock, so parallel requests cannot
 * slip past it. A vouch is addressed by its own ID and only its voucher can
 * withdraw it; anyone else gets the same 404 as for an ID that does not
 * exist.
 */
@Service
@EnableConfigurationProperties(VerificationLimitProperties.class)
public class VouchService {

    static final String BUCKET = "vouch-account";

    private final UserRepository users;

    private final VouchRepository vouches;

    private final TrustInputEvents trustInputs;

    private final RateLimiter rateLimiter;

    private final VerificationLimitProperties limits;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public VouchService(UserRepository users, VouchRepository vouches, TrustInputEvents trustInputs,
                        RateLimiter rateLimiter, VerificationLimitProperties limits,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.vouches = vouches;
        this.trustInputs = trustInputs;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VouchSummary summary(SessionPrincipal caller) {
        User user = users.findById(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        List<Vouch> given = vouches.findActiveGivenBy(user.getId());
        Map<UUID, User> vouched = users.findAllById(given.stream().map(Vouch::getVouchedUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<GivenVouch> rows = given.stream()
                .map(vouch -> new GivenVouch(vouch, firstName(vouched.get(vouch.getVouchedUserId()))))
                .toList();
        return new VouchSummary(canVouch(user), limits.maxActiveVouches(), rows,
                vouches.countActiveReceivedBy(user.getId()));
    }

    public GivenVouch give(SessionPrincipal caller, UUID vouchedUserId) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), limits.vouch());
        if (caller.userId().equals(vouchedUserId)) {
            throw new ApiProblemException(VerificationProblem.CANNOT_VOUCH_FOR_SELF);
        }
        return transactions.execute(status -> {
            User voucher = users.findByIdForUpdate(caller.userId())
                    .filter(User::isActive)
                    .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
            if (!canVouch(voucher)) {
                throw new ApiProblemException(VerificationProblem.VOUCH_REQUIRES_SELFIE_MATCH);
            }
            User vouched = users.findById(vouchedUserId)
                    .filter(User::isActive)
                    .filter(User::isEmailVerified)
                    .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
            Vouch existing = vouches.findByVoucherUserIdAndVouchedUserId(voucher.getId(), vouched.getId())
                    .orElse(null);
            if (existing != null && existing.isActive()) {
                throw new ApiProblemException(VerificationProblem.ALREADY_VOUCHING);
            }
            if (vouches.countActiveGivenBy(voucher.getId()) >= limits.maxActiveVouches()) {
                throw new ApiProblemException(VerificationProblem.VOUCH_LIMIT_REACHED);
            }
            Instant now = clock.instant();
            Vouch vouch;
            if (existing == null) {
                vouch = vouches.save(Vouch.give(voucher.getId(), vouched.getId(), now));
            } else {
                existing.renew(now);
                vouch = existing;
            }
            trustInputs.vouchGiven(vouch.getId(), voucher.getId(), vouched.getId());
            return new GivenVouch(vouch, firstName(vouched));
        });
    }

    /** Only the voucher can withdraw a vouch; for anyone else, and for a withdrawn one, it does not exist. */
    public void revoke(SessionPrincipal caller, UUID vouchId) {
        transactions.executeWithoutResult(status -> {
            users.findByIdForUpdate(caller.userId())
                    .filter(User::isActive)
                    .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
            Vouch vouch = vouches.findById(vouchId)
                    .filter(found -> found.getVoucherUserId().equals(caller.userId()))
                    .filter(Vouch::isActive)
                    .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
            vouch.revoke(clock.instant());
            trustInputs.vouchRevoked(vouch.getId(), vouch.getVoucherUserId(), vouch.getVouchedUserId());
        });
    }

    /**
     * Records a penalty for everyone actively vouching for an account that
     * has just been suspended, banned or reported past the threshold. Runs in
     * the transaction that makes that change, so the penalty cannot be lost
     * or applied twice relative to it.
     *
     * @return how many vouchers were penalised
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int penalizeVouchersOf(UUID penalizedUserId) {
        List<UUID> vouchers = vouches.findActiveVouchersOf(penalizedUserId);
        vouchers.forEach(voucher -> trustInputs.vouchPenalized(voucher, penalizedUserId));
        return vouchers.size();
    }

    private static boolean canVouch(User user) {
        return user.getIdVerificationTier() == VerificationTier.SELFIE_MATCH;
    }

    /** The voucher knows who they vouched for; the first name is enough to recognise them, and no more is shown. */
    private static String firstName(User user) {
        if (user == null) {
            return "";
        }
        String name = user.getFullName().strip();
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    public record GivenVouch(UUID id, UUID vouchedUserId, String vouchedFirstName, Instant since) {

        GivenVouch(Vouch vouch, String firstName) {
            this(vouch.getId(), vouch.getVouchedUserId(), firstName, vouch.getCreatedAt());
        }
    }

    /**
     * @param canVouch      the caller has reached SELFIE_MATCH
     * @param limit         how many people one account can vouch for at once
     * @param receivedCount how many people actively vouch for the caller
     */
    public record VouchSummary(boolean canVouch, int limit, List<GivenVouch> given, long receivedCount) {
    }
}
