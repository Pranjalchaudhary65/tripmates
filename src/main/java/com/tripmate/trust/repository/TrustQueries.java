package com.tripmate.trust.repository;

import com.tripmate.trust.service.TrustScoreFormula.Input;
import com.tripmate.trust.service.TrustScoreFormula.InputType;
import com.tripmate.trust.service.TrustScoreFormula.Rating;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The trust module's own tables, in SQL: input events, the recompute queue,
 * snapshots, and the visible half of reviews. Events and snapshots are only
 * ever inserted (V21 refuses anything else).
 */
@Repository
public class TrustQueries {

    private static final List<String> SCORED_TYPES = Arrays.stream(InputType.values()).map(Enum::name).toList();

    private final NamedParameterJdbcTemplate jdbc;

    public TrustQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Once per outbox event: a redelivery finds the row already there and records nothing. */
    public void recordEvent(UUID userId, String type, String detail, UUID relatedId, UUID sourceEventId,
                            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO trust_score_events (user_id, event_type, detail, related_id, source_event_id, occurred_at)
                VALUES (:user, :type, :detail, :related, :source, :at)
                ON CONFLICT (source_event_id) DO NOTHING""", new MapSqlParameterSource("user", userId)
                .addValue("type", type).addValue("detail", detail).addValue("related", relatedId)
                .addValue("source", sourceEventId).addValue("at", Timestamp.from(occurredAt)));
    }

    /**
     * Queues the users for a recompute. In ID order, so two transactions
     * marking overlapping people lock their rows in the same order. An
     * already-queued user keeps the earlier time, so nobody is pushed back.
     */
    public void markDirty(Collection<UUID> userIds, Instant now) {
        userIds.stream().distinct().sorted().forEach(userId -> jdbc.update("""
                INSERT INTO trust_score_state (user_id, dirty_since) VALUES (:user, :now)
                ON CONFLICT (user_id) DO UPDATE
                    SET dirty_since = COALESCE(trust_score_state.dirty_since, EXCLUDED.dirty_since)""",
                new MapSqlParameterSource("user", userId).addValue("now", Timestamp.from(now))));
    }

    /**
     * Queues every score last computed before {@code staleBefore}, since
     * ratings and penalties fade with time, and every active, email-verified
     * account that has never been queued at all.
     *
     * @return how many were queued
     */
    public int queueStaleAndMissing(Instant now, Instant staleBefore) {
        MapSqlParameterSource params = new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("before", Timestamp.from(staleBefore));
        int stale = jdbc.update("""
                UPDATE trust_score_state SET dirty_since = :now
                WHERE dirty_since IS NULL AND (computed_at IS NULL OR computed_at < :before)""", params);
        int missing = jdbc.update("""
                INSERT INTO trust_score_state (user_id, dirty_since)
                SELECT f.user_id, :now FROM user_public_facts f
                WHERE f.active AND f.id_verification_tier <> 'NONE'
                  AND NOT EXISTS (SELECT 1 FROM trust_score_state s WHERE s.user_id = f.user_id)
                ON CONFLICT (user_id) DO NOTHING""", params);
        return stale + missing;
    }

    /** The longest-waiting queued user, row-locked; another instance skips it. */
    public Optional<UUID> claimDirty() {
        return jdbc.queryForList("""
                SELECT user_id FROM trust_score_state WHERE dirty_since IS NOT NULL
                ORDER BY dirty_since, user_id LIMIT 1 FOR UPDATE SKIP LOCKED""", new MapSqlParameterSource(),
                UUID.class).stream().findFirst();
    }

    public void markComputed(UUID userId, Instant now) {
        jdbc.update("UPDATE trust_score_state SET dirty_since = NULL, computed_at = :now WHERE user_id = :user",
                new MapSqlParameterSource("user", userId).addValue("now", Timestamp.from(now)));
    }

    /** Visible reviews only: a review still hidden by the double-blind can never reach a score. */
    public List<Rating> visibleRatings(UUID userId) {
        return jdbc.query("""
                SELECT rating, visible_at FROM reviews
                WHERE reviewee_id = :user AND visible_at IS NOT NULL""", new MapSqlParameterSource("user", userId),
                (rs, row) -> new Rating(rs.getInt("rating"), rs.getTimestamp("visible_at").toInstant()));
    }

    public List<Input> scoredInputs(UUID userId) {
        return jdbc.query("""
                SELECT event_type, detail, occurred_at FROM trust_score_events
                WHERE user_id = :user AND event_type IN (:types)
                ORDER BY occurred_at, id""", new MapSqlParameterSource("user", userId).addValue("types", SCORED_TYPES),
                (rs, row) -> new Input(InputType.valueOf(rs.getString("event_type")), rs.getString("detail"),
                        rs.getTimestamp("occurred_at").toInstant()));
    }

    /**
     * Writes a snapshot unless the latest one already says exactly this, so a
     * daily refresh that changes nothing adds no row.
     *
     * @return true when a snapshot was written
     */
    public boolean snapshotIfChanged(UUID userId, BigDecimal score, String componentsJson, String formulaVersion,
                                     Instant now) {
        return jdbc.update("""
                INSERT INTO trust_score_snapshots (user_id, score, components, formula_version, computed_at)
                SELECT :user, :score, CAST(:components AS jsonb), :version, :now
                WHERE NOT EXISTS (
                    SELECT 1 FROM (SELECT score, components, formula_version FROM trust_score_snapshots
                                   WHERE user_id = :user ORDER BY computed_at DESC, id DESC LIMIT 1) latest
                    WHERE latest.score = :score AND latest.components = CAST(:components AS jsonb)
                      AND latest.formula_version = :version)""", new MapSqlParameterSource("user", userId)
                .addValue("score", score).addValue("components", componentsJson).addValue("version", formulaVersion)
                .addValue("now", Timestamp.from(now))) == 1;
    }

    public Optional<Snapshot> latestSnapshot(UUID userId) {
        return jdbc.query("""
                SELECT score, components::text AS components, formula_version, computed_at
                FROM trust_score_snapshots WHERE user_id = :user ORDER BY computed_at DESC, id DESC LIMIT 1""",
                new MapSqlParameterSource("user", userId),
                (rs, row) -> new Snapshot(rs.getBigDecimal("score"), rs.getString("components"),
                        rs.getString("formula_version"), rs.getTimestamp("computed_at").toInstant()))
                .stream().findFirst();
    }

    public record Snapshot(BigDecimal score, String componentsJson, String formulaVersion, Instant computedAt) {
    }
}
