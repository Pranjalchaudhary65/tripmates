package com.tripmate.platform.audit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes the audit log (DESIGN.md §9). Requires the caller's transaction, so
 * a row is written exactly when the change it records commits. The database
 * chains it onto the previous row (V23's trigger); nothing here can choose a
 * hash or a sequence number.
 *
 * <p>Metadata is IDs, codes and numbers only, never names, emails, phone
 * numbers or free text: the audit log outlives account deletion, so it must
 * hold nothing that deletion is meant to remove.
 */
@Component
public class AuditLog {

    private static final Pattern EVENT = Pattern.compile("[A-Z_]{1,50}");

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{0,64}");

    private final NamedParameterJdbcTemplate jdbc;

    private final JsonMapper json;

    private final Clock clock;

    public AuditLog(NamedParameterJdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param actorUserId who did it; null for the system (a job, an automatic rule)
     * @param metadata    plain values only; each must be an ID, code or number
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorUserId, AuditEvent event, String entityType, UUID entityId,
                       Map<String, ?> metadata) {
        Objects.requireNonNull(event, "event");
        if (!EVENT.matcher(event.name()).matches()) {
            throw new IllegalArgumentException("Bad audit event name");
        }
        Map<String, String> clean = new TreeMap<>();
        metadata.forEach((key, value) -> {
            String text = String.valueOf(value);
            if (!SAFE_VALUE.matcher(key).matches() || !SAFE_VALUE.matcher(text).matches()) {
                throw new IllegalArgumentException("Audit metadata must be IDs, codes or numbers: " + key);
            }
            clean.put(key, text);
        });
        jdbc.update("""
                INSERT INTO audit_log (sequence_no, prev_hash, row_hash, actor_user_id, event_type, entity_type,
                                       entity_id, metadata, created_at)
                VALUES (0, :zero, :zero, :actor, :event, :entityType, :entityId, CAST(:metadata AS jsonb), :now)""",
                new MapSqlParameterSource("zero", "0".repeat(64)).addValue("actor", actorUserId, Types.OTHER)
                        .addValue("event", event.name()).addValue("entityType", entityType, Types.VARCHAR)
                        .addValue("entityId", entityId, Types.OTHER).addValue("metadata", json.writeValueAsString(clean))
                        .addValue("now", Timestamp.from(clock.instant())));
    }

    /** Shorthand for an entry with no metadata. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorUserId, AuditEvent event, String entityType, UUID entityId) {
        record(actorUserId, event, entityType, entityId, Map.of());
    }
}
