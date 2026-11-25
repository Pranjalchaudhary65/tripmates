package com.tripmate.safety.entity;

/**
 * A point as stored: two-decimal coordinates in the clear (about a
 * kilometre, enough for "roughly where") and the exact point sealed under
 * the traveller's key, opened only for an emergency alert.
 */
public record RoundedLocation(double latitude, double longitude, byte[] ciphertext) {

    public static double round(double coordinate) {
        return Math.round(coordinate * 100) / 100.0;
    }
}
