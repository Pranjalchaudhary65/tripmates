package com.tripmate.identity.verification;

import java.awt.image.BufferedImage;

/**
 * Decides whether two frames show a live person following a prompt, and how
 * closely that person matches the profile photo. Chosen by
 * {@code tripmate.identity.selfie.liveness-provider}; a commercial
 * liveness/face-match service is a new implementation registered under its
 * own value. Called outside any database transaction, since a real provider
 * is a network call.
 */
public interface LivenessVerifier {

    Assessment assess(Evidence evidence);

    /**
     * All three images have been through the image sanitiser.
     *
     * @param neutralFrame the face looking at the camera; the one compared and stored
     * @param promptFrame  the face doing what {@code prompt} asked; checked, then discarded
     * @param reference    the current profile photo
     */
    record Evidence(LivenessPrompt prompt, BufferedImage neutralFrame, BufferedImage promptFrame,
                    BufferedImage reference) {
    }

    /**
     * @param live       the frames show a live capture following the prompt
     * @param similarity 0 to 1: how closely the neutral frame matches the profile photo
     */
    record Assessment(boolean live, double similarity) {

        public Assessment {
            if (similarity < 0 || similarity > 1 || Double.isNaN(similarity)) {
                throw new IllegalArgumentException("similarity must be between 0 and 1");
            }
        }
    }
}
