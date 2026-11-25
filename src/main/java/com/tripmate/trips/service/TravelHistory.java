package com.tripmate.trips.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * How many trips a person has actually travelled to the end, for the trust
 * score. No endpoint reaches this.
 *
 * <p>A trip counts once it has ended (its last day is before today, IST) and
 * was not cancelled, for its host when at least one companion was still on
 * it, and for each companion still on it. A companion who stepped back for
 * their safety is not on it: that trip neither counts for nor against them,
 * so stepping back stays free.
 */
@Service
public class TravelHistory {

    private final NamedParameterJdbcTemplate jdbc;

    public TravelHistory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int finishedTrips(UUID userId, LocalDate today) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM trips t
                WHERE t.status <> 'CANCELLED' AND t.end_date < :today
                  AND EXISTS (SELECT 1 FROM trip_members m
                              WHERE m.trip_id = t.id AND m.status IN ('LOCKED', 'COMPLETED')
                                AND (t.host_user_id = :user OR m.user_id = :user))""",
                new MapSqlParameterSource("user", userId).addValue("today", today), Integer.class);
        return count == null ? 0 : count;
    }
}
