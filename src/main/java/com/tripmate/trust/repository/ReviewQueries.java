package com.tripmate.trust.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reviews in SQL. Other people's trips and names come from the views trips
 * and identity publish ({@code trip_facts}, {@code trip_member_facts},
 * {@code user_public_facts}), never their tables.
 *
 * <p>Only {@link #byTrip} returns hidden reviews, and only to the service
 * that decides, row by row, what the caller may see; every other read here
 * filters on {@code visible_at}.
 */
@Repository
public class ReviewQueries {

    private static final RowMapper<ReviewRow> ROW = (rs, row) -> new ReviewRow(rs.getObject("id", UUID.class),
            rs.getObject("trip_id", UUID.class), rs.getObject("reviewer_id", UUID.class),
            rs.getObject("reviewee_id", UUID.class), rs.getInt("rating"), rs.getString("comment"),
            rs.getTimestamp("submitted_at").toInstant(), instant(rs.getTimestamp("visible_at")));

    private final NamedParameterJdbcTemplate jdbc;

    public ReviewQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serialises the two people of one pair on one trip for the rest of the
     * transaction, whichever of them is writing, so two reviews submitted at
     * the same moment cannot each miss the other and both stay hidden.
     */
    public void lockPair(UUID tripId, UUID one, UUID other) {
        String low = one.compareTo(other) < 0 ? one.toString() : other.toString();
        String high = one.compareTo(other) < 0 ? other.toString() : one.toString();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "review:" + tripId + ":" + low + ":" + high), Object.class);
    }

    /** Every review on the trip, hidden ones included: for the service to filter for its caller. */
    public List<ReviewRow> byTrip(UUID tripId) {
        return jdbc.query("SELECT * FROM reviews WHERE trip_id = :trip ORDER BY submitted_at, id",
                new MapSqlParameterSource("trip", tripId), ROW);
    }

    public void insert(UUID tripId, UUID reviewerId, UUID revieweeId, int rating, String comment, Instant now) {
        jdbc.update("""
                INSERT INTO reviews (trip_id, reviewer_id, reviewee_id, rating, comment, submitted_at)
                VALUES (:trip, :reviewer, :reviewee, :rating, :comment, :now)""",
                new MapSqlParameterSource("trip", tripId).addValue("reviewer", reviewerId)
                        .addValue("reviewee", revieweeId).addValue("rating", rating).addValue("comment", comment)
                        .addValue("now", Timestamp.from(now)));
    }

    /** Both reviews of the pair become visible together, in the transaction that wrote the second. */
    public void revealPair(UUID tripId, UUID one, UUID other, Instant now) {
        jdbc.update("""
                UPDATE reviews SET visible_at = :now
                WHERE trip_id = :trip AND visible_at IS NULL
                  AND ((reviewer_id = :one AND reviewee_id = :other) OR (reviewer_id = :other AND reviewee_id = :one))""",
                new MapSqlParameterSource("trip", tripId).addValue("one", one).addValue("other", other)
                        .addValue("now", Timestamp.from(now)));
    }

    /**
     * Reveals every review still hidden on a trip whose review window has
     * closed: the window ends {@code windowDays} after the start of the day
     * after the trip's last day, IST.
     *
     * @return the people whose received reviews just became visible
     */
    public List<UUID> revealClosedWindows(Instant now, int windowDays) {
        return jdbc.queryForList("""
                UPDATE reviews r SET visible_at = :now
                FROM trip_facts t
                WHERE r.trip_id = t.trip_id AND r.visible_at IS NULL
                  AND ((t.end_date + 1)::timestamp AT TIME ZONE 'Asia/Kolkata') + make_interval(days => :days) <= :now
                RETURNING r.reviewee_id""", new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("days", windowDays), UUID.class);
    }

    /**
     * Trips the user travelled on to the end whose review window is open
     * today: ended, not cancelled, at most {@code windowDays} days ago.
     */
    public List<UUID> tripsInReviewWindow(UUID userId, LocalDate today, int windowDays) {
        return jdbc.queryForList("""
                SELECT t.trip_id FROM trip_facts t
                WHERE t.status <> 'CANCELLED' AND t.end_date < :today AND t.end_date >= :earliest
                  AND EXISTS (SELECT 1 FROM trip_member_facts m
                              WHERE m.trip_id = t.trip_id AND m.status IN ('LOCKED', 'COMPLETED')
                                AND (t.host_user_id = :user OR m.user_id = :user))
                ORDER BY t.end_date DESC, t.trip_id""", new MapSqlParameterSource("user", userId)
                .addValue("today", today).addValue("earliest", today.minusDays(windowDays)), UUID.class);
    }

    /** Visible reviews about the user, newest first, with the trip and the reviewer's first name. */
    public List<ReceivedReview> receivedVisible(UUID userId, int limit) {
        return jdbc.query("""
                SELECT r.trip_id, t.title, t.destination_name, u.first_name, r.rating, r.comment, r.visible_at
                FROM reviews r
                JOIN trip_facts t ON t.trip_id = r.trip_id
                JOIN user_public_facts u ON u.user_id = r.reviewer_id
                WHERE r.reviewee_id = :user AND r.visible_at IS NOT NULL
                ORDER BY r.visible_at DESC, r.id
                LIMIT :limit""", new MapSqlParameterSource("user", userId).addValue("limit", limit),
                (rs, row) -> new ReceivedReview(rs.getObject("trip_id", UUID.class), rs.getString("title"),
                        rs.getString("destination_name"), rs.getString("first_name"), rs.getInt("rating"),
                        rs.getString("comment"), rs.getTimestamp("visible_at").toInstant()));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** @param visibleAt null while hidden by the double-blind */
    public record ReviewRow(UUID id, UUID tripId, UUID reviewerId, UUID revieweeId, int rating, String comment,
                            Instant submittedAt, Instant visibleAt) {

        public boolean isVisible() {
            return visibleAt != null;
        }
    }

    public record ReceivedReview(UUID tripId, String tripTitle, String destinationName, String reviewerFirstName,
                                 int rating, String comment, Instant visibleAt) {
    }
}
