package com.tripmate.moderation.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/** Reports in SQL. V22 refuses deleting a report or changing anything but closing it. */
@Repository
public class ReportQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasOpenReport(UUID reporterId, UUID reportedId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM reports
                               WHERE reporter_id = :reporter AND reported_id = :reported AND status = 'OPEN')""",
                new MapSqlParameterSource("reporter", reporterId).addValue("reported", reportedId), Boolean.class));
    }

    public void insert(UUID id, UUID reporterId, UUID reportedId, UUID tripId, String reason, byte[] details,
                       Instant now) {
        jdbc.update("""
                INSERT INTO reports (id, reporter_id, reported_id, trip_id, reason, details_ciphertext, created_at)
                VALUES (:id, :reporter, :reported, :trip, :reason, :details, :now)""",
                new MapSqlParameterSource("id", id).addValue("reporter", reporterId).addValue("reported", reportedId)
                        .addValue("trip", tripId).addValue("reason", reason).addValue("details", details, Types.BINARY)
                        .addValue("now", Timestamp.from(now)));
    }

    /** Different people who reported the account since {@code since}, not counting reports a moderator dismissed. */
    public int distinctReportersSince(UUID reportedId, Instant since) {
        Integer count = jdbc.queryForObject("""
                SELECT count(DISTINCT reporter_id) FROM reports
                WHERE reported_id = :reported AND created_at >= :since AND status <> 'DISMISSED'""",
                new MapSqlParameterSource("reported", reportedId).addValue("since", Timestamp.from(since)),
                Integer.class);
        return count == null ? 0 : count;
    }
}
