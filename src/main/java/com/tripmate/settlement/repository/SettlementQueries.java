package com.tripmate.settlement.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The SQL behind the ledger that is not a plain entity read: the per-trip
 * write lock, people's first names (from identity's {@code user_public_facts}
 * view, never {@code users}), and the overdue sweep (with trips'
 * {@code trip_facts} view for end dates).
 */
@Repository
public class SettlementQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public SettlementQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the trip's ledger row on first use, then locks it for the rest
     * of the caller's transaction. Every write to one trip's ledger and
     * settlements goes through here first, so they run one at a time and
     * each sees the state the last one committed.
     */
    public void lockTripLedger(UUID tripId) {
        MapSqlParameterSource params = new MapSqlParameterSource("tripId", tripId);
        jdbc.update("INSERT INTO trip_ledgers (trip_id) VALUES (:tripId) ON CONFLICT (trip_id) DO NOTHING", params);
        jdbc.queryForObject("SELECT trip_id FROM trip_ledgers WHERE trip_id = :tripId FOR UPDATE", params, UUID.class);
    }

    /** First names by user ID; a user with no row (which should not happen) is simply missing. */
    public Map<UUID, String> firstNames(Collection<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT user_id, first_name FROM user_public_facts WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                rs -> {
                    names.put(rs.getObject("user_id", UUID.class), rs.getString("first_name"));
                });
        return names;
    }

    /**
     * People who have left a settlement undone long enough to count as
     * ghosting it, and have not been reported for that trip and role yet:
     * a payer whose suggested payment has stood since before
     * {@code proposedBefore} on a trip that ended before {@code endedBefore},
     * and a payee who has not confirmed a payment marked as sent before
     * {@code markedBefore}.
     */
    public List<Overdue> overdue(LocalDate endedBefore, Instant proposedBefore, Instant markedBefore, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("endedBefore", endedBefore)
                .addValue("proposedBefore", Timestamp.from(proposedBefore))
                .addValue("markedBefore", Timestamp.from(markedBefore))
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT DISTINCT candidates.trip_id, candidates.user_id, candidates.role
                FROM (
                    SELECT s.trip_id, s.from_user_id AS user_id, 'PAYER' AS role
                    FROM settlements s
                    JOIN trip_facts t ON t.trip_id = s.trip_id
                    WHERE s.status = 'PROPOSED' AND s.created_at < :proposedBefore AND t.end_date < :endedBefore
                    UNION
                    SELECT s.trip_id, s.to_user_id AS user_id, 'PAYEE' AS role
                    FROM settlements s
                    WHERE s.status = 'MARKED_PAID' AND s.marked_paid_at < :markedBefore
                ) candidates
                WHERE NOT EXISTS (
                    SELECT 1 FROM settlement_overdue_notices n
                    WHERE n.trip_id = candidates.trip_id AND n.user_id = candidates.user_id
                      AND n.role = candidates.role)
                ORDER BY candidates.trip_id, candidates.user_id, candidates.role
                LIMIT :limit""", params, (rs, row) -> new Overdue(rs.getObject("trip_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("role")));
    }

    /** True only for the caller that recorded it first, so each notice is published once. */
    public boolean recordOverdue(UUID tripId, UUID userId, String role) {
        return jdbc.update("""
                INSERT INTO settlement_overdue_notices (trip_id, user_id, role) VALUES (:tripId, :userId, :role)
                ON CONFLICT DO NOTHING""", new MapSqlParameterSource()
                .addValue("tripId", tripId).addValue("userId", userId).addValue("role", role)) == 1;
    }

    /** @param role PAYER or PAYEE */
    public record Overdue(UUID tripId, UUID userId, String role) {
    }
}
