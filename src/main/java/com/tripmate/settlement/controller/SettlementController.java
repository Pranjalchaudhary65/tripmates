package com.tripmate.settlement.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.settlement.dto.LedgerResponse;
import com.tripmate.settlement.dto.SettlementMapper;
import com.tripmate.settlement.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Actions on one settlement or one expense, addressed by its own ID. Each is
 * authorised through the trip it belongs to: a trip member gets a 409 for an
 * action that is not theirs to take, anyone else the 404 an unknown ID gets.
 * Every action answers with the whole settle-up screen, so the client
 * refreshes in one call.
 */
@RestController
@Tag(name = "Settle-up", description = "Shared expenses and who pays whom. TripMates never holds money.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = SettlementController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "not_found: no such settlement or expense, or not on a trip the "
        + "caller travels on", content = @Content(mediaType = SettlementController.PROBLEM_JSON,
        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = SettlementController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class SettlementController {

    static final String PROBLEM_JSON = "application/problem+json";

    private final LedgerService ledger;

    private final SettlementMapper mapper;

    public SettlementController(LedgerService ledger, SettlementMapper mapper) {
        this.ledger = ledger;
        this.mapper = mapper;
    }

    @PostMapping("/settlements/{settlementId}/mark-paid")
    @Operation(summary = "The payer says they sent the money", description = "Repeating it changes nothing")
    @ApiResponse(responseCode = "200", description = "The ledger")
    @ApiResponse(responseCode = "409", description = "not_the_payer or settlement_closed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public LedgerResponse markPaid(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                   @PathVariable UUID settlementId) {
        return mapper.toResponse(ledger.markPaid(SessionPrincipal.of(token), settlementId));
    }

    @PostMapping("/settlements/{settlementId}/confirm")
    @Operation(summary = "The payee confirms they received it", description = "The only step that writes the "
            + "ledger. Only after the payer marked it as sent; never by the payer. Repeating it changes nothing.")
    @ApiResponse(responseCode = "200", description = "The ledger")
    @ApiResponse(responseCode = "409", description = "cannot_confirm_own_payment, not_the_payee, not_marked_paid "
            + "or settlement_closed", content = @Content(mediaType = PROBLEM_JSON,
            schema = @Schema(implementation = ProblemDetail.class)))
    public LedgerResponse confirm(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                  @PathVariable UUID settlementId) {
        return mapper.toResponse(ledger.confirm(SessionPrincipal.of(token), settlementId));
    }

    @PostMapping("/settlements/{settlementId}/dispute")
    @Operation(summary = "Say a payment is wrong", description = "Either person. The amount is held out of the "
            + "plan and a moderator looks at it.")
    @ApiResponse(responseCode = "200", description = "The ledger")
    @ApiResponse(responseCode = "409", description = "not_a_party or settlement_closed",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public LedgerResponse dispute(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                  @PathVariable UUID settlementId) {
        return mapper.toResponse(ledger.dispute(SessionPrincipal.of(token), settlementId));
    }

    @PostMapping("/expenses/{expenseId}/reverse")
    @Operation(summary = "Correct an expense by reversing it", description = "Records its exact opposite; the "
            + "expense stays in the history. Only whoever recorded it, and only once.")
    @ApiResponse(responseCode = "200", description = "The ledger")
    @ApiResponse(responseCode = "409", description = "not_your_expense or not_an_expense",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public LedgerResponse reverse(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                  @PathVariable UUID expenseId) {
        return mapper.toResponse(ledger.reverseExpense(SessionPrincipal.of(token), expenseId));
    }
}
