package com.tripmate.safety.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.safety.entity.Checkin;
import com.tripmate.safety.entity.RoundedLocation;
import com.tripmate.safety.entity.SosEvent;
import com.tripmate.safety.service.LiveTripViews.ReportedLocation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A reported point split into what is kept in the clear (two decimals,
 * about a kilometre) and the exact point sealed under the traveller's key.
 * Each sealed value is bound to its row by its context, so it opens only as
 * the location of that check-in or that SOS.
 */
final class Locations {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private Locations() {
    }

    static RoundedLocation seal(UserDataCipher cipher, UUID userId, EncryptedField field, String context,
                                ReportedLocation reported) {
        String exact = new Exact(reported.latitude(), reported.longitude(), reported.accuracyMeters()).format();
        return new RoundedLocation(RoundedLocation.round(reported.latitude()),
                RoundedLocation.round(reported.longitude()),
                cipher.seal(userId, field, context, exact.getBytes(StandardCharsets.UTF_8)));
    }

    static String checkinContext(UUID tripId, UUID userId, Instant respondedAt) {
        return "checkin:" + tripId + ":" + userId + ":" + respondedAt.toEpochMilli();
    }

    static String checkinContext(Checkin checkin) {
        return checkinContext(checkin.getTripId(), checkin.getUserId(), checkin.getRespondedAt());
    }

    static String sosContext(UUID userId, UUID clientSosId) {
        return "sos:" + userId + ":" + clientSosId;
    }

    static String sosContext(SosEvent sos) {
        return sosContext(sos.getUserId(), sos.getClientSosId());
    }

    static String noteContext(UUID tripId) {
        return "itinerary:" + tripId;
    }

    /** Great-circle distance in kilometres. */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** The exact point, as sealed: "latitude,longitude[,accuracy]". */
    record Exact(double latitude, double longitude, Double accuracyMeters) {

        String format() {
            return String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude)
                    + (accuracyMeters == null ? "" : String.format(Locale.ROOT, ",%.0f", accuracyMeters));
        }

        static Exact parse(String text) {
            String[] parts = text.split(",");
            return new Exact(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    parts.length > 2 ? Double.valueOf(parts[2]) : null);
        }
    }
}
