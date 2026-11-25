package com.tripmate.identity.service;

import com.tripmate.platform.audit.AuditEvent;
import com.tripmate.platform.audit.AuditLog;
import com.tripmate.identity.entity.IdentityVerification;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.VerificationStatus;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.repository.IdentityVerificationRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.identity.upload.SignedMediaUrls;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The review queue: only selfies the liveness check could not decide on its
 * own. Every method starts with {@link AdminGuard}. A reviewer sees the
 * selfie next to the profile photo it was compared against, through signed
 * links that expire in minutes, and can never decide their own.
 */
@Service
public class AdminVerificationService {

    public static final int MAX_PAGE_SIZE = 50;

    private final AdminGuard adminGuard;

    private final IdentityVerificationRepository verifications;

    private final UserRepository users;

    private final SignedMediaUrls mediaUrls;

    private final TrustInputEvents trustInputs;

    private final AuditLog audit;

    private final Clock clock;

    public AdminVerificationService(AdminGuard adminGuard, IdentityVerificationRepository verifications,
                                    UserRepository users, SignedMediaUrls mediaUrls, TrustInputEvents trustInputs,
                                    Clock clock, AuditLog audit) {
        this.audit = audit;
        this.adminGuard = adminGuard;
        this.verifications = verifications;
        this.users = users;
        this.mediaUrls = mediaUrls;
        this.trustInputs = trustInputs;
        this.clock = clock;
    }

    /** Pending selfies, oldest first: the one closest to its purge deadline is reviewed first. */
    @Transactional(readOnly = true)
    public Page<ReviewItem> queue(SessionPrincipal caller, int page, int size) {
        adminGuard.requireAdmin(caller);
        Page<IdentityVerification> pending = verifications.findByTierAndStatusOrderByCreatedAtAsc(
                VerificationTier.SELFIE_MATCH, VerificationStatus.PENDING,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)));
        Map<UUID, User> owners = users.findAllById(pending.map(IdentityVerification::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return pending.map(verification -> toItem(verification, owners.get(verification.getUserId())));
    }

    /**
     * Approves or rejects a pending selfie. The owner's row is locked before
     * the verification's, the same order every other writer uses, so a
     * decision racing a profile photo change cannot deadlock with it.
     */
    @Transactional
    public ReviewItem decide(SessionPrincipal caller, UUID verificationId, boolean approve) {
        User reviewer = adminGuard.requireAdmin(caller);
        UUID ownerId = verifications.findById(verificationId)
                .filter(found -> found.getTier() == VerificationTier.SELFIE_MATCH)
                .map(IdentityVerification::getUserId)
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
        if (ownerId.equals(reviewer.getId())) {
            throw new ApiProblemException(VerificationProblem.CANNOT_REVIEW_OWN);
        }
        User owner = users.findByIdForUpdate(ownerId).orElseThrow();
        IdentityVerification verification = verifications.findByIdForUpdate(verificationId).orElseThrow();
        if (!verification.isPending()) {
            throw new ApiProblemException(VerificationProblem.VERIFICATION_ALREADY_DECIDED);
        }
        Instant now = clock.instant();
        verification.decide(approve, reviewer.getId(), now);
        audit.record(reviewer.getId(), AuditEvent.ADMIN_VERIFICATION_DECIDED,
                "VERIFICATION", verificationId, Map.of("decision", approve ? "APPROVE" : "REJECT"));
        if (approve && owner.isActive() && owner.getIdVerificationTier() != VerificationTier.SELFIE_MATCH) {
            VerificationTier before = owner.markSelfieMatched(now);
            trustInputs.tierChanged(owner.getId(), before, owner.getIdVerificationTier());
        }
        return toItem(verification, owner);
    }

    private ReviewItem toItem(IdentityVerification verification, User owner) {
        SignedMediaUrls.SignedMediaUrl selfie = verification.getSelfieImageKey() == null ? null
                : mediaUrls.sign(verification.getSelfieImageKey());
        SignedMediaUrls.SignedMediaUrl photo = owner == null || owner.getProfilePhotoKey() == null ? null
                : mediaUrls.sign(owner.getProfilePhotoKey());
        return new ReviewItem(verification.getId(), verification.getUserId(),
                owner == null ? "" : owner.getFullName(), verification.getStatus(),
                verification.getSimilarityScore(), verification.getCreatedAt(), verification.getPurgeDueAt(),
                selfie, photo);
    }

    /**
     * @param selfie       null once purged
     * @param profilePhoto the photo the selfie was compared against
     */
    public record ReviewItem(UUID verificationId, UUID userId, String fullName, VerificationStatus status,
                             BigDecimal similarityScore, Instant submittedAt, Instant purgeDueAt,
                             SignedMediaUrls.SignedMediaUrl selfie, SignedMediaUrls.SignedMediaUrl profilePhoto) {
    }
}
