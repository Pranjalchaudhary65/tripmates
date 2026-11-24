package com.tripmate.trips.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * match_score_cache, read and written in SQL. A plain read-through cache:
 * rows are replaced, never versioned, and whether a row is still good is
 * decided by its caller from the stamps it returns.
 */
@Repository
public class MatchCacheRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public MatchCacheRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The viewer's cached rows for these trips, by trip ID; trips with none are absent. */
    public Map<UUID, CachedMatch> find(UUID viewer, Collection<UUID> tripIds) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CachedMatch> rows = new HashMap<>();
        jdbc.query("""
                SELECT trip_id, score, explanation::text AS explanation, scorer, viewer_profile_at, host_profile_at
                FROM match_score_cache
                WHERE user_id = :viewer AND trip_id IN (:trips)""",
                new MapSqlParameterSource("viewer", viewer).addValue("trips", Set.copyOf(tripIds)), rs -> {
                    rows.put(rs.getObject("trip_id", UUID.class), new CachedMatch(rs.getInt("score"),
                            rs.getString("explanation"), rs.getString("scorer"),
                            rs.getObject("viewer_profile_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("host_profile_at", OffsetDateTime.class).toInstant()));
                });
        return Map.copyOf(rows);
    }

    /** Inserts or replaces the viewer's row for the trip; two viewers racing on the same row both succeed. */
    public void put(UUID tripId, UUID viewer, CachedMatch match, Instant now) {
        jdbc.update("""
                INSERT INTO match_score_cache (trip_id, user_id, score, explanation, scorer, viewer_profile_at,
                                               host_profile_at, computed_at)
                VALUES (:trip, :viewer, :score, CAST(:explanation AS jsonb), :scorer, :viewerAt, :hostAt, :now)
                ON CONFLICT (trip_id, user_id) DO UPDATE
                SET score = EXCLUDED.score, explanation = EXCLUDED.explanation, scorer = EXCLUDED.scorer,
                    viewer_profile_at = EXCLUDED.viewer_profile_at, host_profile_at = EXCLUDED.host_profile_at,
                    computed_at = EXCLUDED.computed_at""",
                new MapSqlParameterSource()
                        .addValue("trip", tripId)
                        .addValue("viewer", viewer)
                        .addValue("score", match.score())
                        .addValue("explanation", match.explanationJson())
                        .addValue("scorer", match.scorer())
                        .addValue("viewerAt", utc(match.viewerProfileAt()))
                        .addValue("hostAt", utc(match.hostProfileAt()))
                        .addValue("now", utc(now)));
    }

    /** timestamptz in and out as an offset date-time, so no JVM time zone is ever involved. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * @param explanationJson {"matches": [...], "mismatches": [...]}
     * @param scorer          the scorer that computed it
     * @param viewerProfileAt the viewer's profile updated_at it was computed from
     * @param hostProfileAt   the host's profile updated_at it was computed from
     */
    public record CachedMatch(int score, String explanationJson, String scorer, Instant viewerProfileAt,
                              Instant hostProfileAt) {

        /** Still describes these two profiles as the given scorer would score them. */
        public boolean isFreshFor(String currentScorer, Instant viewerAt, Instant hostAt) {
            return scorer.equals(currentScorer) && viewerProfileAt.equals(viewerAt) && hostProfileAt.equals(hostAt);
        }
    }
}
