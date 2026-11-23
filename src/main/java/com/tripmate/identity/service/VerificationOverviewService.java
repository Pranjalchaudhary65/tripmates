package com.tripmate.identity.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.IdentityVerification;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.VerificationRejection;
import com.tripmate.identity.entity.VerificationStatus;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.phone.PhoneNumbers;
import com.tripmate.identity.repository.IdentityVerificationRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.repository.VouchRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.identity.upload.SignedMediaUrls;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Everything the verification centre shows, in one read: the tier, each
 * step's state, the profile photo link and the vouch counts. The phone number
 * is decrypted here only to be masked; the full number never leaves the
 * server after it is typed.
 */
@Service
@EnableConfigurationProperties(VerificationLimitProperties.class)
public class VerificationOverviewService {

    private final UserRepository users;

    private final IdentityVerificationRepository verifications;

    private final VouchRepository vouches;

    private final FieldEncryption encryption;

    private final SignedMediaUrls mediaUrls;

    private final VerificationLimitProperties limits;

    public VerificationOverviewService(UserRepository users, IdentityVerificationRepository verifications,
                                       VouchRepository vouches, FieldEncryption encryption,
                                       SignedMediaUrls mediaUrls, VerificationLimitProperties limits) {
        this.users = users;
        this.verifications = verifications;
        this.vouches = vouches;
        this.encryption = encryption;
        this.mediaUrls = mediaUrls;
        this.limits = limits;
    }

    @Transactional(readOnly = true)
    public Overview overview(SessionPrincipal caller) {
        User user = users.findById(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        String maskedPhone = user.getPhoneCiphertext() == null ? null : PhoneNumbers.mask(new String(
                encryption.decrypt(user, EncryptedField.PHONE, user.getPhoneCiphertext()), StandardCharsets.UTF_8));
        SignedMediaUrls.SignedMediaUrl photo = user.getProfilePhotoKey() == null ? null
                : mediaUrls.sign(user.getProfilePhotoKey());
        boolean selfieMatched = user.getIdVerificationTier() == VerificationTier.SELFIE_MATCH;
        SelfieState selfie = verifications.findFirstByUserIdAndTierOrderByCreatedAtDesc(user.getId(),
                        VerificationTier.SELFIE_MATCH)
                .filter(latest -> selfieMatched || latest.getStatus() != VerificationStatus.APPROVED)
                .map(SelfieState::of)
                .orElse(SelfieState.NONE);
        return new Overview(user.getIdVerificationTier(), user.isEmailVerified(),
                new PhoneState(user.isPhoneVerified(), maskedPhone), photo, selfie,
                new VouchCounts(selfieMatched, vouches.countActiveGivenBy(user.getId()), limits.maxActiveVouches(),
                        vouches.countActiveReceivedBy(user.getId())));
    }

    /**
     * @param verified     a code sent to the number was confirmed
     * @param maskedNumber the confirmed or pending number, masked; null when none was given
     */
    public record PhoneState(boolean verified, String maskedNumber) {
    }

    /**
     * The newest selfie attempt; a null status means there is none that
     * counts. After a profile photo change drops the tier, the old APPROVED
     * row is shown as none, since it was a match against a photo no longer
     * on the account.
     */
    public record SelfieState(VerificationStatus status, VerificationRejection rejectionReason, Instant submittedAt) {

        static final SelfieState NONE = new SelfieState(null, null, null);

        static SelfieState of(IdentityVerification verification) {
            return new SelfieState(verification.getStatus(), verification.getRejectionReason(),
                    verification.getCreatedAt());
        }
    }

    public record VouchCounts(boolean canVouch, long activeGiven, int limit, long received) {
    }

    public record Overview(VerificationTier tier, boolean emailVerified, PhoneState phone,
                           SignedMediaUrls.SignedMediaUrl profilePhoto, SelfieState selfie, VouchCounts vouches) {
    }
}
