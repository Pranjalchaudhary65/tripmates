package com.tripmate.identity.service;

import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.VerificationRejection;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.repository.IdentityVerificationRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.identity.upload.ImageSanitizer;
import com.tripmate.identity.upload.MediaKind;
import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.identity.upload.SignedMediaUrls;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * The photo other users see, and the one a selfie is compared against.
 *
 * <p>Replacing it ends any claim the old photo earned: a SELFIE_MATCH
 * account drops to PHONE, and a selfie waiting for review is withdrawn,
 * because both were judged against a picture that is no longer shown. Without
 * this, someone could match a selfie against their own photo and then swap
 * in a stranger's while keeping the badge.
 */
@Service
@EnableConfigurationProperties(VerificationLimitProperties.class)
public class ProfilePhotoService {

    static final String BUCKET = "profile-photo-account";

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

    private final UserRepository users;

    private final IdentityVerificationRepository verifications;

    private final ImageSanitizer sanitizer;

    private final ObjectStorage storage;

    private final SignedMediaUrls mediaUrls;

    private final TrustInputEvents trustInputs;

    private final RateLimiter rateLimiter;

    private final VerificationLimitProperties limits;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public ProfilePhotoService(UserRepository users, IdentityVerificationRepository verifications,
                               ImageSanitizer sanitizer, ObjectStorage storage, SignedMediaUrls mediaUrls,
                               TrustInputEvents trustInputs, RateLimiter rateLimiter,
                               VerificationLimitProperties limits, PlatformTransactionManager transactionManager,
                               Clock clock) {
        this.users = users;
        this.verifications = verifications;
        this.sanitizer = sanitizer;
        this.storage = storage;
        this.mediaUrls = mediaUrls;
        this.trustInputs = trustInputs;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Sanitises and stores the new photo, then points the account at it. The
     * file is written before the transaction and removed again if the
     * transaction fails; the old file is removed only after the new one is
     * committed, so the account never points at a missing photo.
     */
    public SignedMediaUrls.SignedMediaUrl replace(SessionPrincipal caller, byte[] upload) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), limits.profilePhoto());
        ImageSanitizer.SanitizedImage image = sanitizer.sanitize(upload);
        String key = MediaKind.PROFILE_PHOTO.newKey();
        storage.put(key, image.jpeg());
        String previousKey;
        try {
            previousKey = transactions.execute(status -> {
                User user = users.findByIdForUpdate(caller.userId())
                        .filter(User::isActive)
                        .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
                Instant now = clock.instant();
                String previous = user.getProfilePhotoKey();
                verifications.findPendingSelfieForUpdate(user.getId())
                        .ifPresent(pending -> pending.withdraw(VerificationRejection.PROFILE_PHOTO_CHANGED, now));
                VerificationTier before = user.replaceProfilePhoto(key, now);
                trustInputs.tierChanged(user.getId(), before, user.getIdVerificationTier());
                return previous;
            });
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
        if (previousKey != null) {
            deleteQuietly(previousKey);
        }
        return mediaUrls.sign(key);
    }

    /** The account already points elsewhere; a leftover file is harmless and is not worth failing the request. */
    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("A replaced profile photo could not be deleted: {}", e.getClass().getSimpleName());
        }
    }
}
