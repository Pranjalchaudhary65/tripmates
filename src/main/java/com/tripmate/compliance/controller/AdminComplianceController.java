package com.tripmate.compliance.controller;

import com.tripmate.compliance.service.AdminAuditService;
import com.tripmate.compliance.service.DataRequestService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.audit.AuditIntegrity.CheckResult;
import com.tripmate.platform.audit.AuditQueries.Entry;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.web.PageResponse;
import com.tripmate.platform.web.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The audit log and data requests, for moderators. Under /admin, so the
 * filter chain already demands ADMIN with a second factor; each service
 * call checks again against the database.
 */
@RestController
@Tag(name = "Admin: audit and data", description = "Audit log, its integrity, and data requests")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminComplianceController {

    private final AdminAuditService audit;

    private final DataRequestService requests;

    public AdminComplianceController(AdminAuditService audit, DataRequestService requests) {
        this.audit = audit;
        this.requests = requests;
    }

    @GetMapping("/admin/audit")
    @Operation(summary = "The audit log, newest first", description = "Optionally only rows about one ID, as the "
            + "entity or the actor; with the latest integrity check")
    public AuditPage log(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                         @RequestParam(required = false) UUID entityId,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        AdminAuditService.AuditView view = audit.page(SessionPrincipal.of(token), entityId,
                Paging.of(page, size));
        return new AuditPage(PageResponse.of(view.page(), AuditItem::of),
                view.latestCheck() == null ? null : Check.of(view.latestCheck()));
    }

    @PostMapping("/admin/audit/integrity-check")
    @Operation(summary = "Check the whole audit chain now", description = "Recomputes every hash")
    public Check check(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return Check.of(audit.checkNow(SessionPrincipal.of(token)));
    }

    @GetMapping("/admin/data-requests")
    @Operation(summary = "Data requests, newest first", description = "Status only; never an export's contents")
    public PageResponse<DataRequestController.Item> dataRequests(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(requests.forAdmin(SessionPrincipal.of(token), Paging.of(page, size)),
                DataRequestController.Item::of);
    }

    public record AuditPage(PageResponse<AuditItem> entries, Check latestCheck) {
    }

    /** @param metadata the row's metadata as a JSON object string: IDs and codes only */
    public record AuditItem(long sequenceNo, UUID actorUserId, String eventType, String entityType, UUID entityId,
                            String metadata, Instant createdAt, String rowHash) {

        static AuditItem of(Entry entry) {
            return new AuditItem(entry.sequenceNo(), entry.actorUserId(), entry.eventType(), entry.entityType(),
                    entry.entityId(), entry.metadataJson(), entry.createdAt(), entry.rowHash());
        }
    }

    public record Check(Instant checkedAt, long rowsChecked, boolean intact, Long firstBrokenSeq) {

        static Check of(CheckResult result) {
            return new Check(result.checkedAt(), result.rowsChecked(), result.intact(), result.firstBrokenSeq());
        }
    }
}