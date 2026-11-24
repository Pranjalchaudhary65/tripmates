package com.tripmate.meetup.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Sunrise and sunset for a point on a date, from the NOAA solar position
 * equations (accurate to about a minute between the polar circles, which is
 * far tighter than any rule built on top of it needs).
 *
 * <p>Pure arithmetic in UTC: no time zone enters the calculation. The date is
 * the venue's local calendar date; the result is two instants.
 */
public final class SolarDaylight {

    /** The sun's centre 0.833 degrees below the horizon: refraction plus its radius, the standard sunrise. */
    private static final double SUNRISE_ZENITH_DEGREES = 90.833;

    private SolarDaylight() {
    }

    /**
     * @return empty where the sun does not both rise and set that day (polar
     *         day or night), so no daylight window can be offered there
     */
    public static Optional<Window> on(LocalDate date, double latitude, double longitude) {
        double julianDay = date.toEpochDay() + 2440587.5 + 0.5;
        double julianCentury = (julianDay - 2451545.0) / 36525.0;

        double meanLongitude = normalise(280.46646 + julianCentury * (36000.76983 + julianCentury * 0.0003032));
        double meanAnomaly = 357.52911 + julianCentury * (35999.05029 - 0.0001537 * julianCentury);
        double eccentricity = 0.016708634 - julianCentury * (0.000042037 + 0.0000001267 * julianCentury);
        double anomalyRadians = Math.toRadians(meanAnomaly);
        double centre = Math.sin(anomalyRadians) * (1.914602 - julianCentury * (0.004817 + 0.000014 * julianCentury))
                + Math.sin(2 * anomalyRadians) * (0.019993 - 0.000101 * julianCentury)
                + Math.sin(3 * anomalyRadians) * 0.000289;
        double trueLongitude = meanLongitude + centre;
        double omega = 125.04 - 1934.136 * julianCentury;
        double apparentLongitude = trueLongitude - 0.00569 - 0.00478 * Math.sin(Math.toRadians(omega));
        double meanObliquity = 23 + (26 + (21.448 - julianCentury * (46.815 + julianCentury * (0.00059
                - julianCentury * 0.001813))) / 60) / 60;
        double obliquity = meanObliquity + 0.00256 * Math.cos(Math.toRadians(omega));
        double declination = Math.asin(Math.sin(Math.toRadians(obliquity))
                * Math.sin(Math.toRadians(apparentLongitude)));

        double y = Math.pow(Math.tan(Math.toRadians(obliquity / 2)), 2);
        double meanLongitudeRadians = Math.toRadians(meanLongitude);
        double equationOfTimeMinutes = 4 * Math.toDegrees(y * Math.sin(2 * meanLongitudeRadians)
                - 2 * eccentricity * Math.sin(anomalyRadians)
                + 4 * eccentricity * y * Math.sin(anomalyRadians) * Math.cos(2 * meanLongitudeRadians)
                - 0.5 * y * y * Math.sin(4 * meanLongitudeRadians)
                - 1.25 * eccentricity * eccentricity * Math.sin(2 * anomalyRadians));

        double latitudeRadians = Math.toRadians(latitude);
        double cosHourAngle = Math.cos(Math.toRadians(SUNRISE_ZENITH_DEGREES))
                / (Math.cos(latitudeRadians) * Math.cos(declination))
                - Math.tan(latitudeRadians) * Math.tan(declination);
        if (cosHourAngle < -1 || cosHourAngle > 1) {
            return Optional.empty();
        }
        double hourAngleDegrees = Math.toDegrees(Math.acos(cosHourAngle));

        double solarNoonMinutes = 720 - 4 * longitude - equationOfTimeMinutes;
        double sunriseMinutes = solarNoonMinutes - 4 * hourAngleDegrees;
        double sunsetMinutes = solarNoonMinutes + 4 * hourAngleDegrees;
        Instant midnightUtc = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return Optional.of(new Window(midnightUtc.plusSeconds(Math.round(sunriseMinutes * 60)),
                midnightUtc.plusSeconds(Math.round(sunsetMinutes * 60))));
    }

    private static double normalise(double degrees) {
        double value = degrees % 360;
        return value < 0 ? value + 360 : value;
    }

    public record Window(Instant sunrise, Instant sunset) {
    }
}
