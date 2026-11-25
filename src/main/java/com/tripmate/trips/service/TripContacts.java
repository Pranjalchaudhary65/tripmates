package com.tripmate.trips.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a person has actually dealt with through a trip, for the moderation
 * module: you can block or report only someone you were matched with (a host
 * and a companion they accepted, whatever happened after) or travelled with.
 * That keeps both from being aimed at a stranger by ID, and means a report
 * always comes from someone with first-hand contact. No endpoint reaches
 * this.
 */
@Service
public class TripContacts {

    private final NamedParameterJdbcTemplate jdbc;

    public TripContacts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The other side of a match (a join request the host accepted), seen from
     * either side. Empty when the caller is not on it, or it never got past
     * the request.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Contact> otherSideOfMatch(UUID caller, UUID matchId) {
        List<Contact> rows = jdbc.query("""
                SELECT m.trip_id,
                       CASE WHEN t.host_user_id = :caller THEN m.user_id ELSE t.host_user_id END AS other
                FROM trip_members m JOIN trips t ON t.id = m.trip_id
                WHERE m.id = :match AND m.status IN ('ACCEPTED', 'LOCKED', 'CANCELLED', 'COMPLETED')
                  AND (t.host_user_id = :caller OR m.user_id = :caller)""",
                new MapSqlParameterSource("caller", caller).addValue("match", matchId),
                (rs, row) -> new Contact(rs.getObject("other", UUID.class), rs.getObject("trip_id", UUID.class)));
        return rows.stream().findFirst();
    }

    /**
     * Someone the caller was locked in on a trip with, the host included.
     * Empty when either of them never travelled on it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Contact> fellowTraveller(UUID caller, UUID tripId, UUID other) {
        if (caller.equals(other)) {
            return Optional.empty();
        }
        Boolean both = jdbc.queryForObject("""
                SELECT count(*) = 2 FROM (
                    SELECT t.host_user_id AS user_id FROM trips t
                    WHERE t.id = :trip AND EXISTS (SELECT 1 FROM trip_members m WHERE m.trip_id = t.id
                                                   AND m.locked_at IS NOT NULL)
                    UNION
                    SELECT m.user_id FROM trip_members m WHERE m.trip_id = :trip AND m.locked_at IS NOT NULL
                ) travellers WHERE user_id IN (:caller, :other)""",
                new MapSqlParameterSource("trip", tripId).addValue("caller", caller).addValue("other", other),
                Boolean.class);
        return Boolean.TRUE.equals(both) ? Optional.of(new Contact(other, tripId)) : Optional.empty();
    }

    public record Contact(UUID userId, UUID tripId) {
    }
}
