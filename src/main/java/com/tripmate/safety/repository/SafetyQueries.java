package com.tripmate.safety.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** First names from identity's {@code user_public_facts} view; nothing here reads {@code users}. */
@Repository
public class SafetyQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public SafetyQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
}
