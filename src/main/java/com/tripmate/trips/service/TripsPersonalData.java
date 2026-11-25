package com.tripmate.trips.service;

import com.tripmate.platform.privacy.PersonalDataContributor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** The trips a user hosted and the ones they asked to join, in a data export. */
@Component
public class TripsPersonalData implements PersonalDataContributor {

    private final NamedParameterJdbcTemplate jdbc;

    public TripsPersonalData(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "trips";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Object export(UUID userId) {
        MapSqlParameterSource user = new MapSqlParameterSource("user", userId);
        return Map.of(
                "hosted", jdbc.queryForList("""
                        SELECT id::text AS id, title, description, destination_name, start_date::text AS start_date,
                               end_date::text AS end_date, budget_band, total_slots, status,
                               created_at::text AS created_at
                        FROM trips WHERE host_user_id = :user ORDER BY created_at""", user),
                "joinRequests", jdbc.queryForList("""
                        SELECT m.trip_id::text AS trip_id, t.title, m.status, m.requested_at::text AS requested_at,
                               m.decided_at::text AS decided_at, m.locked_at::text AS locked_at
                        FROM trip_members m JOIN trips t ON t.id = m.trip_id
                        WHERE m.user_id = :user ORDER BY m.requested_at""", user));
    }
}