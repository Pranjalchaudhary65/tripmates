package com.tripmate.trust.service;

import com.tripmate.platform.privacy.PersonalDataContributor;
import com.tripmate.platform.privacy.PersonalDataEraser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Reviews and score history in a data export: reviews the user wrote, and
 * reviews about them only once visible, so an export can never get round
 * the double-blind. On deletion the comments they wrote are erased; the
 * ratings stay, as part of other people's scores.
 */
@Component
public class TrustPersonalData implements PersonalDataContributor, PersonalDataEraser {

    private final NamedParameterJdbcTemplate jdbc;

    public TrustPersonalData(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "trust";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Object export(UUID userId) {
        MapSqlParameterSource user = new MapSqlParameterSource("user", userId);
        return Map.of(
                "reviewsWritten", jdbc.queryForList("""
                        SELECT trip_id::text AS trip_id, rating, comment, submitted_at::text AS submitted_at
                        FROM reviews WHERE reviewer_id = :user ORDER BY submitted_at""", user),
                "reviewsReceived", jdbc.queryForList("""
                        SELECT trip_id::text AS trip_id, rating, comment, visible_at::text AS visible_at
                        FROM reviews WHERE reviewee_id = :user AND visible_at IS NOT NULL
                        ORDER BY visible_at""", user),
                "scoreHistory", jdbc.queryForList("""
                        SELECT score, components::text AS components, computed_at::text AS computed_at
                        FROM trust_score_snapshots WHERE user_id = :user ORDER BY computed_at""", user));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID userId) {
        jdbc.update("UPDATE reviews SET comment = NULL WHERE reviewer_id = :user AND comment IS NOT NULL",
                new MapSqlParameterSource("user", userId));
    }
}