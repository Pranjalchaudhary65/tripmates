package com.tripmate.compliance.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.identity.service.AccountDeletion;
import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.AdminGuard;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.audit.AuditEvent;
import com.tripmate.platform.audit.AuditLog;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.error.Constraints;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's requests for a copy of their data, or to delete their account
 * (DESIGN.md §9). The request is recorded at once and done by
 * {@link DataRequestJob}, off the request path. Deleting needs the account's
 * password again, so a stolen session alone cannot erase someone. A request
 * is the user's own: anyone else gets the 404 an unknown ID gets. An export
 * is sealed under the user's key and can be downloaded for
 * {@code export-ttl}.
 */
@Service
@EnableConfigurationProperties(ComplianceProperties.class)
public class DataRequestService {

    static final String BUCKET = "data-request";

    private static final RowMapper<DataRequest> ROW = (rs, row) -> new DataRequest(rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class), RequestType.valueOf(rs.getString("type")),
            RequestStatus.valueOf(rs.getString("status")), rs.getTimestamp("requested_at").toInstant(),
            instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("expires_at")),
            rs.getBoolean("downloadable"));

    private static final String COLUMNS = """
            id, user_id, type, status, requested_at, completed_at, expires_at,
            (export_ciphertext IS NOT NULL AND expires_at > :now) AS downloadable""";

    private final NamedParameterJdbcTemplate jdbc;

    private final AccountService accounts;

    private final AccountDeletion deletion;

    private final AdminGuard adminGuard;

    private final UserDataCipher cipher;

    private final AuditLog audit;

    private final RateLimiter rateLimiter;

    private final ComplianceProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public DataRequestService(NamedParameterJdbcTemplate jdbc, AccountService accounts, AccountDeletion deletion,
                              AdminGuard adminGuard, UserDataCipher cipher, AuditLog audit, RateLimiter rateLimiter,
                              ComplianceProperties properties, PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.deletion = deletion;
        this.adminGuard = adminGuard;
        this.cipher = cipher;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public DataRequest request(SessionPrincipal caller, RequestType type, String password) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), properties.requestLimit());
        UUID me = caller.userId();
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                if (type == RequestType.DELETION) {
                    deletion.requirePassword(caller, password);
                } else {
                    accounts.lockActive(caller);
                }
                Instant now = clock.instant();
                UUID id = jdbc.queryForObject("""
                        INSERT INTO data_requests (user_id, type, requested_at) VALUES (:user, :type, :now)
                        RETURNING id""", new MapSqlParameterSource("user", me).addValue("type", type.name())
                        .addValue("now", Timestamp.from(now)), UUID.class);
                audit.record(me, type == RequestType.EXPORT ? AuditEvent.DATA_EXPORT_REQUESTED
                        : AuditEvent.ACCOUNT_DELETION_REQUESTED, "DATA_REQUEST", id);
                return find(me, id);
            }));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, "uq_data_requests_pending")) {
                throw new ApiProblemException(ComplianceProblem.DATA_REQUEST_PENDING);
            }
            throw e;
        }
    }

    public List<DataRequest> mine(SessionPrincipal caller) {
        return Objects.requireNonNull(transactions.execute(status -> {
            accounts.lockActive(caller);
            return jdbc.query("SELECT " + COLUMNS + " FROM data_requests WHERE user_id = :user "
                    + "ORDER BY requested_at DESC LIMIT 50", params(caller.userId()), ROW);
        }));
    }

    /** The decrypted export, as JSON bytes. */
    public byte[] download(SessionPrincipal caller, UUID requestId) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), properties.requestLimit());
        UUID me = caller.userId();
        return Objects.requireNonNull(transactions.execute(status -> {
            accounts.lockActive(caller);
            List<byte[]> sealed = jdbc.query("""
                    SELECT export_ciphertext FROM data_requests
                    WHERE id = :id AND user_id = :user AND type = 'EXPORT'""",
                    params(me).addValue("id", requestId), (rs, row) -> rs.getBytes(1));
            if (sealed.isEmpty()) {
                throw new ApiProblemException(CommonProblem.NOT_FOUND);
            }
            DataRequest request = find(me, requestId);
            if (!request.downloadable() || sealed.getFirst() == null) {
                throw new ApiProblemException(ComplianceProblem.EXPORT_NOT_READY);
            }
            byte[] json = cipher.open(me, EncryptedField.DATA_EXPORT, context(requestId), sealed.getFirst())
                    .orElseThrow(() -> new ApiProblemException(ComplianceProblem.EXPORT_NOT_READY));
            audit.record(me, AuditEvent.DATA_EXPORT_DOWNLOADED, "DATA_REQUEST", requestId);
            return json;
        }));
    }

    /** Every request, newest first, for moderators overseeing them; never the export itself. */
    public Page<DataRequest> forAdmin(SessionPrincipal caller, Pageable pageable) {
        return Objects.requireNonNull(transactions.execute(status -> {
            adminGuard.requireAdmin(caller);
            MapSqlParameterSource params = new MapSqlParameterSource("now", Timestamp.from(clock.instant()))
                    .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
            Long total = jdbc.queryForObject("SELECT count(*) FROM data_requests", params, Long.class);
            List<DataRequest> rows = jdbc.query("SELECT " + COLUMNS + " FROM data_requests "
                    + "ORDER BY requested_at DESC, id LIMIT :limit OFFSET :offset", params, ROW);
            return new PageImpl<>(rows, pageable, total == null ? 0 : total);
        }));
    }

    static String context(UUID requestId) {
        return "data-export:" + requestId;
    }

    private DataRequest find(UUID userId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM data_requests WHERE id = :id AND user_id = :user",
                params(userId).addValue("id", id), ROW).stream().findFirst()
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
    }

    private MapSqlParameterSource params(UUID userId) {
        return new MapSqlParameterSource("user", userId).addValue("now", Timestamp.from(clock.instant()));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public enum RequestType {
        EXPORT, DELETION
    }

    public enum RequestStatus {
        PENDING, COMPLETED, FAILED
    }

    /** @param downloadable an export that is ready and not expired */
    public record DataRequest(UUID id, UUID userId, RequestType type, RequestStatus status, Instant requestedAt,
                              Instant completedAt, Instant expiresAt, boolean downloadable) {
    }
}
