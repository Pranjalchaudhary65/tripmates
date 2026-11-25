package com.tripmate.moderation.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.moderation.service.AdminModerationService;
import com.tripmate.moderation.service.AdminModerationService.ReportItem;
import com.tripmate.moderation.service.AdminModerationService.ReportStatus;
import com.tripmate.moderation.service.ModerationProblem;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.web.PageResponse;
import com.tripmate.platform.web.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.UUID;

/**
 * Reports and account suspensions, for moderators. Under /admin, so the
 * filter chain already demands ADMIN with a second factor; the service
 * checks again against the database.
 */
@RestController
@Tag(name = "Admin: reports", description = "The report queue and account suspensions")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminModerationController {

    private final AdminModerationService moderation;

    public AdminModerationController(AdminModerationService moderation) {
        this.moderation = moderation;
    }

    @GetMapping("/admin/reports")
    @Operation(summary = "Reports in one state, oldest first", description = "With the details opened for the "
            + "moderator")
    public PageResponse<ReportItem> reports(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                            @RequestParam(defaultValue = "OPEN") String status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(moderation.queue(SessionPrincipal.of(token), parse(status), Paging.of(page, size)),
                item -> item);
    }

    @PostMapping("/admin/reports/{reportId}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Close a report", description = "ACTIONED or DISMISSED; suspend also suspends the reported "
            + "account")
    public void decide(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token, @PathVariable UUID reportId,
                       @RequestBody Decision decision) {
        moderation.decide(SessionPrincipal.of(token), reportId, parse(decision.decision()),
                Boolean.TRUE.equals(decision.suspend()));
    }

    @PostMapping("/admin/users/{userId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Suspend an account", description = "Ends its sessions and penalises its vouchers")
    public void suspend(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token, @PathVariable UUID userId) {
        moderation.suspend(SessionPrincipal.of(token), userId);
    }

    @PostMapping("/admin/users/{userId}/reinstate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Lift a suspension")
    public void reinstate(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token, @PathVariable UUID userId) {
        moderation.reinstate(SessionPrincipal.of(token), userId);
    }

    private static ReportStatus parse(String raw) {
        return Arrays.stream(ReportStatus.values()).filter(value -> value.name().equals(raw)).findFirst()
                .orElseThrow(() -> new ApiProblemException(ModerationProblem.INVALID_DECISION));
    }

    public record Decision(String decision, Boolean suspend) {
    }
}