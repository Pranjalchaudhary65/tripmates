package com.tripmate.compliance.service;

import com.tripmate.identity.service.AdminGuard;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.audit.AuditEvent;
import com.tripmate.platform.audit.AuditIntegrity;
import com.tripmate.platform.audit.AuditIntegrity.CheckResult;
import com.tripmate.platform.audit.AuditLog;
import com.tripmate.platform.audit.AuditQueries;
import com.tripmate.platform.audit.AuditQueries.Entry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** The audit log for moderators. Every method starts with {@link AdminGuard}. */
@Service
public class AdminAuditService {

    private final AdminGuard adminGuard;

    private final AuditQueries queries;

    private final AuditIntegrity integrity;

    private final AuditLog audit;

    private final TransactionTemplate transactions;

    public AdminAuditService(AdminGuard adminGuard, AuditQueries queries, AuditIntegrity integrity, AuditLog audit,
                             PlatformTransactionManager transactionManager) {
        this.adminGuard = adminGuard;
        this.queries = queries;
        this.integrity = integrity;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AuditView page(SessionPrincipal caller, UUID entityId, Pageable pageable) {
        Page<Entry> page = Objects.requireNonNull(transactions.execute(status -> {
            adminGuard.requireAdmin(caller);
            return queries.page(entityId, pageable);
        }));
        return new AuditView(page, integrity.latest().orElse(null));
    }

    /** Records who ran it, then checks the whole chain, that row included. */
    public CheckResult checkNow(SessionPrincipal caller) {
        transactions.executeWithoutResult(status -> {
            adminGuard.requireAdmin(caller);
            audit.record(caller.userId(), AuditEvent.AUDIT_CHECK_RUN, null, null, Map.of());
        });
        return integrity.check();
    }

    /** @param latestCheck null before any check has run */
    public record AuditView(Page<Entry> page, CheckResult latestCheck) {
    }
}