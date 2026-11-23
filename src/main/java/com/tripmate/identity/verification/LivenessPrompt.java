package com.tripmate.identity.verification;

/**
 * What the user is asked to do between the two frames of a selfie check. It
 * is chosen by the server when the check starts, so frames prepared in
 * advance are unlikely to show the right movement.
 */
public enum LivenessPrompt {
    TURN_HEAD_LEFT, TURN_HEAD_RIGHT, SMILE, BLINK
}
