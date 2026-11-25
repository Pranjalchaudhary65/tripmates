package com.tripmate.moderation.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.identity.service.AccountModeration;
import com.tripmate.identity.service.AdminGuard;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.audit.AuditEvent;
import com.tripmate.platform.audit.AuditLog;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The report queue for moderators: every report with its details opened,
 * the two people by name and the reported person's state, and the decisions
 * that close a report and may suspend or reinstate an account. Every method
 * starts with {@link AdminGuard}; a moderator can never decide about a
 * report they are part of, nor suspend themselves, and every decision is in
 * the audit log.
 */
@Service
public class AdminModerationService {

    private final AdminGuard adminGuard;

    private final AccountModeration accounts;

    private final UserDataCipher cipher;

    private final AuditLog audit;

    private final NamedParameterJdbcTemplate jdbc;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public AdminModerationService(AdminGuard adminGuard, AccountModeration accounts, UserDataCipher cipher,
                                  AuditLog audit, NamedParameterJdbcTemplate jdbc,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.adminGuard = adminGuard;
        this.accounts = accounts;
        this.cipher = cipher;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Reports in one state, oldest first: the longest-waiting report is looked at first. */
    public Page<ReportItem> queue(SessionPrincipal caller, ReportStatus status, Pageable pageable) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            adminGuard.requireAdmin(caller);
            MapSqlParameterSource params = new MapSqlParameterSource("status", status.name())
                    .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
            Long total = jdbc.queryForObject("SELECT count(*) FROM reports WHERE status = :status", params,
                    Long.class);
            List<ReportItem> items = jdbc.query("""
                    SELECT r.id, r.reason, r.status, r.created_at, r.trip_id, r.details_ciphertext,
                           r.reporter_id, rp.first_name AS reporter_name,
                           r.reported_id, rd.first_name AS reported_name,
                           rd.active AS reported_active,
                           (SELECT count(DISTINCT o.reporter_id) FROM reports o
                            WHERE o.reported_id = r.reported_id AND o.status <> 'DISMISSED') AS reporters
                    FROM reports r
                    JOIN user_public_facts rp ON rp.user_id = r.reporter_id
                    JOIN user_public_facts rd ON rd.user_id = r.reported_id
                    WHERE r.status = :status
                    ORDER BY r.created_at, r.id LIMIT :limit OFFSET :offset""", params, (rs, row) -> {
                UUID id = rs.getObject("id", UUID.class);
                UUID reporter = rs.getObject("reporter_id", UUID.class);
                byte[] sealed = rs.getBytes("details_ciphertext");
                String details = sealed == null ? null : cipher.open(reporter, EncryptedField.REPORT_DETAILS,
                        "report:" + id, sealed).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                        .orElse(null);
                return new ReportItem(id, rs.getString("reason"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), rs.getObject("trip_id", UUID.class), details,
                        sealed != null && details == null,
                        new Party(reporter, rs.getString("reporter_name"), null, 0),
                        new Party(rs.getObject("reported_id", UUID.class), rs.getString("reported_name"),
                                rs.getBoolean("reported_active") ? "ACTIVE" : "NOT_ACTIVE", rs.getInt("reporters")));
            });
            return new PageImpl<>(items, pageable, total == null ? 0 : total);
        }));
    }

    /**
     * Closes an open report. With {@code suspend}, the reported account is
     * suspended too (a no-op if it already is).
     */
    public void decide(SessionPrincipal caller, UUID reportId, ReportStatus outcome, boolean suspend) {
        if (outcome == ReportStatus.OPEN) {
            throw new ApiProblemException(ModerationProblem.INVALID_DECISION);
        }
        transactions.executeWithoutResult(tx -> {
            User admin = adminGuard.requireAdmin(caller);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT reporter_id, reported_id, status FROM reports WHERE id = :id FOR UPDATE""",
                    new MapSqlParameterSource("id", reportId));
            if (rows.isEmpty()) {
                throw new ApiProblemException(CommonProblem.NOT_FOUND);
            }
            Map<String, Object> report = rows.getFirst();
            UUID reported = (UUID) report.get("reported_id");
            if (admin.getId().equals(report.get("reporter_id")) || admin.getId().equals(reported)) {
                throw new ApiProblemException(ModerationProblem.CANNOT_MODERATE_SELF);
            }
            if (!"OPEN".equals(report.get("status"))) {
                throw new ApiProblemException(ModerationProblem.REPORT_CLOSED);
            }
            jdbc.update("""
                    UPDATE reports SET status = :status, reviewed_by = :admin, reviewed_at = :now WHERE id = :id""",
                    new MapSqlParameterSource("id", reportId).addValue("status", outcome.name())
                            .addValue("admin", admin.getId()).addValue("now", Timestamp.from(clock.instant())));
            audit.record(admin.getId(), AuditEvent.REPORT_CLOSED, "REPORT", reportId,
                    Map.of("outcome", outcome.name(), "suspend", suspend));
            if (suspend) {
                accounts.suspend(reported, admin.getId());
            }
        });
    }

    public void suspend(SessionPrincipal caller, UUID userId) {
        transactions.executeWithoutResult(tx -> {
            User admin = adminGuard.requireAdmin(caller);
            requireOther(admin, userId);
            accounts.suspend(userId, admin.getId());
        });
    }

    public void reinstate(SessionPrincipal caller, UUID userId) {
        transactions.executeWithoutResult(tx -> {
            User admin = adminGuard.requireAdmin(caller);
            requireOther(admin, userId);
            accounts.reinstate(userId, admin.getId());
        });
    }

    private void requireOther(User admin, UUID userId) {
        if (!accounts.lock(userId)) {
            throw new ApiProblemException(CommonProblem.NOT_FOUND);
        }
        if (admin.getId().equals(userId)) {
            throw new ApiProblemException(ModerationProblem.CANNOT_MODERATE_SELF);
        }
    }

    public enum ReportStatus {
        OPEN, ACTIONED, DISMISSED
    }

    /**
     * @param status    ACTIVE or NOT_ACTIVE (suspended, banned or deleted), for the reported person only
     * @param reporters different people with undismissed reports about them, for the reported person only
     */
    public record Party(UUID userId, String firstName, String status, int reporters) {
    }

    /** @param detailsUnreadable details were written but the reporter's key has been shredded */
    public record ReportItem(UUID id, String reason, String status, Instant createdAt, UUID tripId, String details,
                             boolean detailsUnreadable, Party reporter, Party reported) {
    }
}