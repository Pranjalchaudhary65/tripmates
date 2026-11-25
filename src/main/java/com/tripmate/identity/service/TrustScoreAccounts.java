package com.tripmate.identity.service;

import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.repository.VouchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the trust score needs from identity, and the one write it makes back:
 * the {@code users.trust_score} cache other modules filter on. No endpoint
 * reaches this; the trust module's recompute job is its only caller.
 */
@Service
public class TrustScoreAccounts {

    private final UserRepository users;

    private final VouchRepository vouches;

    public TrustScoreAccounts(UserRepository users, VouchRepository vouches) {
        this.users = users;
        this.vouches = vouches;
    }

    /** Empty for an account that does not exist. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Facts> facts(UUID userId) {
        return users.findById(userId).map(user -> new Facts(user.getIdVerificationTier(),
                vouches.countActiveReceivedBy(userId), user.isActive()));
    }

    /** Updates the cached score in the transaction that wrote its snapshot. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordScore(UUID userId, BigDecimal score, Instant computedAt) {
        users.recordTrustScore(userId, score, computedAt);
    }

    /** The first name, for showing a reviewer or reviewee to someone they travelled with. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> firstName(UUID userId) {
        return users.findById(userId).map(User::getFullName).map(TrustScoreAccounts::firstNameOf);
    }

    static String firstNameOf(String fullName) {
        String name = fullName.strip();
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    /**
     * @param activeVouchers how many people vouch for the account right now
     */
    public record Facts(VerificationTier tier, long activeVouchers, boolean active) {
    }
}
