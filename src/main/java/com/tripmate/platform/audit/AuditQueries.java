package com.tripmate.platform.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reads of the audit log for the admin view, newest first. Who may call this is the caller's to check. */
@Repository
public class AuditQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param entityId optional; with it, only rows about that entity */
    @Transactional(propagation = Propagation.MANDATORY)
    public Page<Entry> page(UUID entityId, Pageable pageable) {
        String where = entityId == null ? "" : "WHERE entity_id = :entity OR actor_user_id = :entity";
        MapSqlParameterSource params = new MapSqlParameterSource("entity", entityId)
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_log " + where, params, Long.class);
        List<Entry> rows = jdbc.query("""
                SELECT sequence_no, actor_user_id, event_type, entity_type, entity_id, metadata::text AS metadata,
                       created_at, row_hash
                FROM audit_log """ + where + " ORDER BY sequence_no DESC LIMIT :limit OFFSET :offset", params,
                (rs, row) -> new Entry(rs.getLong("sequence_no"), rs.getObject("actor_user_id", UUID.class),
                        rs.getString("event_type"), rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                        rs.getString("metadata"), rs.getTimestamp("created_at").toInstant(), rs.getString("row_hash")));
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** @param metadataJson the row's metadata object, IDs and codes only */
    public record Entry(long sequenceNo, UUID actorUserId, String eventType, String entityType, UUID entityId,
                        String metadataJson, Instant createdAt, String rowHash) {
    }
}
