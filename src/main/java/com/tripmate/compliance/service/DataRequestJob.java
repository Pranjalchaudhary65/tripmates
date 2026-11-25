package com.tripmate.compliance.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.identity.service.AccountDeletion;
import com.tripmate.identity.service.AccountModeration;
import com.tripmate.platform.audit.AuditEvent;
import com.tripmate.platform.audit.AuditLog;
import com.tripmate.platform.privacy.PersonalDataContributor;
import com.tripmate.platform.privacy.PersonalDataEraser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Does the data requests users have made, each in its own transaction under
 * {@code FOR UPDATE SKIP LOCKED} so several instances can run it.
 *
 * <ul>
 *   <li>EXPORT: every module's {@link PersonalDataContributor} adds its
 *       section; the JSON is sealed under the user's key and kept for
 *       {@code export-ttl}.</li>
 *   <li>DELETION: every {@link PersonalDataEraser} removes what it holds in
 *       the clear, then identity scrubs the account and shreds its keys,
 *       which also makes any export still waiting unreadable.</li>
 * </ul>
 * A request that throws is retried on later runs and marked FAILED after
 * {@code max-attempts}. Expired exports are cleared every run.
 */
@Component
public class DataRequestJob {

    private static final Logger log = LoggerFactory.getLogger(DataRequestJob.class);

    private final NamedParameterJdbcTemplate jdbc;

    private final List<PersonalDataContributor> contributors;

    private final List<PersonalDataEraser> erasers;

    private final AccountDeletion deletion;

    private final AccountModeration accounts;

    private final UserDataCipher cipher;

    private final AuditLog audit;

    private final JsonMapper json;

    private final ComplianceProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public DataRequestJob(NamedParameterJdbcTemplate jdbc, List<PersonalDataContributor> contributors,
                          List<PersonalDataEraser> erasers, AccountDeletion deletion, AccountModeration accounts,
                          UserDataCipher cipher, AuditLog audit, JsonMapper json, ComplianceProperties properties,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.contributors = contributors.stream().sorted(Comparator.comparing(PersonalDataContributor::section))
                .toList();
        this.erasers = List.copyOf(erasers);
        this.deletion = deletion;
        this.accounts = accounts;
        this.cipher = cipher;
        this.audit = audit;
        this.json = json;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.compliance.job-interval}",
            initialDelayString = "${tripmate.compliance.job-interval}")
    public void run() {
        sweep();
    }

    /** @return how many requests were completed */
    public int sweep() {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE data_requests SET export_ciphertext = NULL
                WHERE export_ciphertext IS NOT NULL AND expires_at <= :now""",
                new MapSqlParameterSource("now", Timestamp.from(clock.instant()))));
        int done = 0;
        Set<UUID> failed = new HashSet<>();
        while (true) {
            Pending[] claimed = new Pending[1];
            String[] photo = new String[1];
            try {
                transactions.executeWithoutResult(status -> {
                    claimed[0] = claim(failed);
                    if (claimed[0] != null) {
                        photo[0] = process(claimed[0]);
                    }
                });
            } catch (RuntimeException failure) {
                if (claimed[0] == null) {
                    throw failure;
                }
                failed.add(claimed[0].id());
                recordFailure(claimed[0], failure);
                continue;
            }
            if (claimed[0] == null) {
                return done;
            }
            if (photo[0] != null) {
                deletion.removeStoredFile(photo[0]);
            }
            done++;
        }
    }

    /** The oldest waiting request not already failed in this run, row-locked; null when none. */
    private Pending claim(Set<UUID> skip) {
        // NOT IN over an empty list is not valid SQL; a random ID stands in for "skip nothing".
        Set<UUID> excluded = skip.isEmpty() ? Set.of(UUID.randomUUID()) : skip;
        List<Pending> rows = jdbc.query("""
                SELECT id, user_id, type FROM data_requests
                WHERE status = 'PENDING' AND id NOT IN (:skip)
                ORDER BY requested_at, id LIMIT 1 FOR UPDATE SKIP LOCKED""",
                new MapSqlParameterSource("skip", excluded), (rs, row) -> new Pending(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("type")));
        return rows.isEmpty() ? null : rows.getFirst();
    }
    /** @return a stored file to remove once this commits, or null */
    private String process(Pending pending) {
        Instant now = clock.instant();
        if ("EXPORT".equals(pending.type())) {
            accounts.lock(pending.userId());
            Map<String, Object> export = new LinkedHashMap<>();
            export.put("generatedAt", now.toString());
            export.put("userId", pending.userId().toString());
            for (PersonalDataContributor contributor : contributors) {
                export.put(contributor.section(), contributor.export(pending.userId()));
            }
            byte[] sealed = cipher.seal(pending.userId(), EncryptedField.DATA_EXPORT,
                    DataRequestService.context(pending.id()),
                    json.writeValueAsString(export).getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                    UPDATE data_requests SET status = 'COMPLETED', completed_at = :now, expires_at = :expires,
                           export_ciphertext = :sealed
                    WHERE id = :id""", new MapSqlParameterSource("id", pending.id())
                    .addValue("now", Timestamp.from(now)).addValue("expires", Timestamp.from(now.plus(properties.exportTtl())))
                    .addValue("sealed", sealed, Types.BINARY));
            audit.record(null, AuditEvent.DATA_EXPORT_COMPLETED, "DATA_REQUEST", pending.id());
            return null;
        }
        erasers.forEach(eraser -> eraser.erase(pending.userId()));
        Optional<String> photo = deletion.delete(pending.userId());
        jdbc.update("""
                UPDATE data_requests SET status = CASE WHEN id = :id THEN 'COMPLETED' ELSE 'FAILED' END,
                       completed_at = :now
                WHERE user_id = :user AND status = 'PENDING'""", new MapSqlParameterSource("id", pending.id())
                .addValue("user", pending.userId()).addValue("now", Timestamp.from(now)));
        audit.record(null, AuditEvent.ACCOUNT_DELETED, "USER", pending.userId());
        return photo.orElse(null);
    }

    private void recordFailure(Pending pending, RuntimeException failure) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE data_requests SET attempts = attempts + 1,
                       status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE status END,
                       completed_at = CASE WHEN attempts + 1 >= :max THEN CAST(:now AS timestamptz) END
                WHERE id = :id AND status = 'PENDING'""", new MapSqlParameterSource("id", pending.id())
                .addValue("max", properties.maxAttempts()).addValue("now", Timestamp.from(clock.instant()))));
        log.warn("Data request {} ({}) failed: {}", pending.id(), pending.type(), failure.getClass().getName());
    }

    private record Pending(UUID id, UUID userId, String type) {
    }
}
