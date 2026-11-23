package com.tripmate.identity.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code tripmate.identity.selfie.liveness-provider=mock}: a stand-in that
 * needs no API key, so the selfie tier works end to end in a demo.
 *
 * <ul>
 *   <li>Liveness: the two frames must differ by at least
 *       {@link #MIN_MOVEMENT_BITS} bits of their difference hashes. The same
 *       still photo sent twice fails; it does not check that the movement
 *       was the one prompted.</li>
 *   <li>Similarity: one minus the share of differing bits between the
 *       neutral frame and the profile photo.</li>
 * </ul>
 *
 * Neither is face recognition, and startup says so. It exists to exercise
 * everything around the provider: thresholds, the review queue, purging.
 */
@Component
@ConditionalOnProperty(prefix = "tripmate.identity.selfie", name = "liveness-provider", havingValue = "mock")
class ImageHashLivenessVerifier implements LivenessVerifier {

    static final int MIN_MOVEMENT_BITS = 4;

    private static final Logger log = LoggerFactory.getLogger(ImageHashLivenessVerifier.class);

    ImageHashLivenessVerifier() {
        log.warn("Selfie checks use the mock liveness provider; it compares images, not faces");
    }

    @Override
    public Assessment assess(Evidence evidence) {
        long neutral = DifferenceHash.of(evidence.neutralFrame());
        long prompted = DifferenceHash.of(evidence.promptFrame());
        long reference = DifferenceHash.of(evidence.reference());
        boolean live = DifferenceHash.distance(neutral, prompted) >= MIN_MOVEMENT_BITS;
        double similarity = 1.0 - (double) DifferenceHash.distance(neutral, reference) / DifferenceHash.BITS;
        return new Assessment(live, similarity);
    }
}
