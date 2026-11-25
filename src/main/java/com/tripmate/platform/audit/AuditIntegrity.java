package com.tripmate.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Walks the whole audit chain and checks it (DESIGN.md §9): sequence numbers
 * run 1, 2, 3 with no gap, each row's prev_hash is the row before's
 * row_hash, and each row_hash is SHA-256 of the row's text, recomputed here
 * in Java rather than trusted from the database. The first row that fails
 * is recorded and logged as an error. Tamper-evidence, not prevention: the
 * triggers prevent; this tells you if someone with enough access got past
 * them.
 */
@Component
public class AuditIntegrity {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrity.class);

    private static final int PAGE = 1000;

    private final NamedParameterJdbcTemplate jdbc;

    private final TransactionTemplate readOnly;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public AuditIntegrity(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.audit.integrity-interval}",
            initialDelayString = "${tripmate.audit.integrity-interval}")
    public void run() {
        check();
    }

    /** Checks the whole chain and records the result. */
    public CheckResult check() {
        MessageDigest sha256 = sha256();
        String expectedPrev = "0".repeat(64);
        long expectedSeq = 1;
        long checked = 0;
        Long broken = null;
        while (broken == null) {
            long from = expectedSeq;
            List<Row> rows = Objects.requireNonNull(readOnly.execute(status -> jdbc.query("""
                    SELECT sequence_no, prev_hash, row_hash,
                           audit_row_text(sequence_no, prev_hash, actor_user_id, event_type, entity_type, entity_id,
                                          metadata, created_at) AS text
                    FROM audit_log WHERE sequence_no >= :from ORDER BY sequence_no LIMIT :page""",
                    new MapSqlParameterSource("from", from).addValue("page", PAGE),
                    (rs, row) -> new Row(rs.getLong("sequence_no"), rs.getString("prev_hash"),
                            rs.getString("row_hash"), rs.getString("text")))));
            if (rows.isEmpty()) {
                break;
            }
            for (Row row : rows) {
                String recomputed = HexFormat.of().formatHex(sha256.digest(row.text().getBytes(StandardCharsets.UTF_8)));
                if (row.sequenceNo() != expectedSeq || !row.prevHash().equals(expectedPrev)
                        || !row.rowHash().equals(recomputed)) {
                    broken = expectedSeq;
                    break;
                }
                checked++;
                expectedPrev = row.rowHash();
                expectedSeq++;
            }
            if (rows.size() < PAGE) {
                break;
            }
        }
        if (broken != null) {
            log.error("Audit log chain is broken at sequence {}; {} rows before it are intact", broken, checked);
        }
        Instant now = clock.instant();
        long rowsChecked = checked;
        Long firstBroken = broken;
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO audit_integrity_checks (checked_at, rows_checked, intact, first_broken_seq)
                VALUES (:now, :rows, :intact, :broken)""", new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("rows", rowsChecked).addValue("intact", firstBroken == null)
                .addValue("broken", firstBroken, java.sql.Types.BIGINT)));
        return new CheckResult(now, rowsChecked, firstBroken == null, firstBroken);
    }

    /** The most recent check, if one has run. */
    public Optional<CheckResult> latest() {
        return Objects.requireNonNull(readOnly.execute(status -> jdbc.query("""
                SELECT checked_at, rows_checked, intact, first_broken_seq FROM audit_integrity_checks
                ORDER BY checked_at DESC, id DESC LIMIT 1""", new MapSqlParameterSource(),
                (rs, row) -> new CheckResult(rs.getTimestamp("checked_at").toInstant(), rs.getLong("rows_checked"),
                        rs.getBoolean("intact"), rs.getObject("first_broken_seq", Long.class))).stream().findFirst()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Row(long sequenceNo, String prevHash, String rowHash, String text) {
    }

    /** @param firstBrokenSeq null when intact */
    public record CheckResult(Instant checkedAt, long rowsChecked, boolean intact, Long firstBrokenSeq) {
    }
}
