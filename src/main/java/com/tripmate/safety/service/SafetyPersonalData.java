package com.tripmate.safety.service;

import com.tripmate.platform.privacy.PersonalDataContributor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The user's check-ins and SOS presses in a data export, with the rounded
 * place only: the exact point is sealed and exists to alert an emergency
 * contact, nothing else.
 */
@Component
public class SafetyPersonalData implements PersonalDataContributor {

    private final NamedParameterJdbcTemplate jdbc;

    public SafetyPersonalData(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "safety";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Object export(UUID userId) {
        MapSqlParameterSource user = new MapSqlParameterSource("user", userId);
        return Map.of(
                "checkIns", jdbc.queryForList("""
                        SELECT trip_id::text AS trip_id, scheduled_at::text AS scheduled_at, status,
                               responded_at::text AS responded_at, rounded_latitude, rounded_longitude
                        FROM checkins WHERE user_id = :user ORDER BY scheduled_at""", user),
                "sos", jdbc.queryForList("""
                        SELECT trip_id::text AS trip_id, triggered_at::text AS triggered_at, status,
                               resolved_at::text AS resolved_at, rounded_latitude, rounded_longitude
                        FROM sos_events WHERE user_id = :user ORDER BY triggered_at""", user));
    }
}