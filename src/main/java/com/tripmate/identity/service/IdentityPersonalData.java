package com.tripmate.identity.service;

import com.tripmate.platform.privacy.PersonalDataContributor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The account in a data export: who the user said they are, how far they are
 * verified, their consents, their vouches and their sign-ins. Secrets
 * (password hash, TOTP secret, phone ciphertext and its blind index) are
 * never exported.
 */
@Component
public class IdentityPersonalData implements PersonalDataContributor {

    private final NamedParameterJdbcTemplate jdbc;

    public IdentityPersonalData(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "account";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Object export(UUID userId) {
        MapSqlParameterSource user = new MapSqlParameterSource("user", userId);
        return Map.of(
                "profile", jdbc.queryForList("""
                        SELECT email::text AS email, full_name, date_of_birth::text AS date_of_birth, gender,
                               id_verification_tier, phone_verified_at IS NOT NULL AS phone_verified, totp_enabled,
                               role, created_at::text AS created_at
                        FROM users WHERE id = :user""", user),
                "consents", jdbc.queryForList("""
                        SELECT consent_type, policy_version, granted, created_at::text AS created_at
                        FROM consent_records WHERE user_id = :user ORDER BY created_at""", user),
                "vouches", jdbc.queryForList("""
                        SELECT CASE WHEN voucher_user_id = :user THEN 'GIVEN' ELSE 'RECEIVED' END AS direction,
                               created_at::text AS created_at, revoked_at::text AS revoked_at
                        FROM vouches WHERE voucher_user_id = :user OR vouched_user_id = :user
                        ORDER BY created_at""", user),
                "signIns", jdbc.queryForList("""
                        SELECT (to_jsonb(h) - 'user_id' - 'id')::text AS sign_in FROM login_history h
                        WHERE h.user_id = :user""", user));
    }
}