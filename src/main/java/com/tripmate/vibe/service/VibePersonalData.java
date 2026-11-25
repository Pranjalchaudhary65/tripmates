package com.tripmate.vibe.service;

import com.tripmate.platform.privacy.PersonalDataContributor;
import com.tripmate.platform.privacy.PersonalDataEraser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** The vibe profile and deal-breakers in a data export, and removed on account deletion. */
@Component
public class VibePersonalData implements PersonalDataContributor, PersonalDataEraser {

    private final NamedParameterJdbcTemplate jdbc;

    public VibePersonalData(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "vibe";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Object export(UUID userId) {
        MapSqlParameterSource user = new MapSqlParameterSource("user", userId);
        return Map.of(
                "profile", jdbc.queryForList("""
                        SELECT budget_band, pace, wake_time, planning_style, social_energy, risk_appetite,
                               food_adventurousness, expense_split_style, updated_at::text AS updated_at
                        FROM vibe_profiles WHERE user_id = :user""", user),
                "dealBreakers", jdbc.queryForList("""
                        SELECT smoking, drinking, gender_preference, min_trust_score, updated_at::text AS updated_at
                        FROM deal_breakers WHERE user_id = :user""", user));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID userId) {
        MapSqlParameterSource user = new MapSqlParameterSource("user", userId);
        jdbc.update("DELETE FROM vibe_quiz_drafts WHERE user_id = :user", user);
        jdbc.update("DELETE FROM deal_breakers WHERE user_id = :user", user);
        jdbc.update("DELETE FROM match_score_cache WHERE user_id = :user", user);
        jdbc.update("DELETE FROM vibe_profiles WHERE user_id = :user", user);
    }
}