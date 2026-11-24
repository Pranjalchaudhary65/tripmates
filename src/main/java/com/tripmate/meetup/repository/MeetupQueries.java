package com.tripmate.meetup.repository;

import com.tripmate.meetup.entity.VenueCategory;
import com.tripmate.identity.entity.VerificationTier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The spatial reads behind the meet-up: allowlisted venues near a point, and
 * how far a check-in point is from a meet-up's venue. Other people come from
 * {@code user_public_facts}, identity's view; nothing here reads {@code users}.
 */
@Repository
public class MeetupQueries {

    private static final String POINT = "ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography";

    private final NamedParameterJdbcTemplate jdbc;

    public MeetupQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Active venues within the radius, nearest first. */
    public List<VenueCard> venuesNear(double latitude, double longitude, double radiusKm, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("latitude", latitude)
                .addValue("longitude", longitude)
                .addValue("radiusMeters", radiusKm * 1000)
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT id, name, address, city, category, latitude, longitude, time_zone,
                       ST_Distance(point, %s) / 1000 AS distance_km
                FROM verified_venues
                WHERE active AND ST_DWithin(point, %s, :radiusMeters)
                ORDER BY distance_km, name
                LIMIT :limit""".formatted(POINT, POINT), params, (rs, row) -> new VenueCard(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getString("city"), VenueCategory.valueOf(rs.getString("category")), rs.getDouble("latitude"),
                rs.getDouble("longitude"), ZoneId.of(rs.getString("time_zone")), rs.getDouble("distance_km")));
    }

    /** Metres from the meet-up's venue to the point, measured on the spheroid. */
    public double distanceFromVenue(UUID meetupId, double latitude, double longitude) {
        Double meters = jdbc.queryForObject("SELECT ST_Distance(venue_point, " + POINT + ") FROM meetups "
                        + "WHERE id = :id",
                new MapSqlParameterSource().addValue("id", meetupId).addValue("latitude", latitude)
                        .addValue("longitude", longitude), Double.class);
        return meters == null ? Double.MAX_VALUE : meters;
    }

    public Optional<Person> person(UUID userId) {
        return jdbc.query("SELECT first_name, id_verification_tier, active FROM user_public_facts WHERE user_id = :id",
                new MapSqlParameterSource("id", userId), (rs, row) -> new Person(rs.getString("first_name"),
                        VerificationTier.valueOf(rs.getString("id_verification_tier")), rs.getBoolean("active")))
                .stream().findFirst();
    }

    public record VenueCard(UUID id, String name, String address, String city, VenueCategory category,
                            double latitude, double longitude, ZoneId zone, double distanceKm) {
    }

    public record Person(String firstName, VerificationTier verificationTier, boolean active) {
    }
}
