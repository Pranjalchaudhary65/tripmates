package com.tripmate.settlement.service;

import com.tripmate.platform.privacy.PersonalDataContributor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Every ledger line on the user's account, in a data export. Amounts in paise. */
@Component
public class SettlementPersonalData implements PersonalDataContributor {

    private final NamedParameterJdbcTemplate jdbc;

    public SettlementPersonalData(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "ledger";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Object export(UUID userId) {
        return Map.of("entries", jdbc.queryForList("""
                SELECT e.trip_id::text AS trip_id, t.kind, t.description, e.entry_type, e.amount_paise, e.currency,
                       e.created_at::text AS created_at
                FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
                WHERE e.account_user_id = :user ORDER BY e.created_at, e.id""",
                new MapSqlParameterSource("user", userId)));
    }
}