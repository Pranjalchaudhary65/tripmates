package com.tripmate.settlement.controller;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.web.PageResponse;
import com.tripmate.platform.web.Paging;
import com.tripmate.settlement.dto.LedgerHistoryItemResponse;
import com.tripmate.settlement.dto.LedgerResponse;
import com.tripmate.settlement.dto.NewExpenseRequest;
import com.tripmate.settlement.dto.SettlementMapper;
import com.tripmate.settlement.service.ExpenseSplit;
import com.tripmate.settlement.service.LedgerService;
import com.tripmate.settlement.service.SettlementProblem;
import com.tripmate.settlement.service.SettlementViews.NewExpense;
import com.tripmate.settlement.service.SettlementViews.ParticipantShare;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A locked trip's shared ledger: the settle-up screen, its history, and
 * recording an expense. Reached only by the host and the companions locked
 * in through a public meet-up; anyone else gets the 404 an unknown trip gets.
 */
@RestController
@RequestMapping(LedgerController.BASE)
@Tag(name = "Settle-up", description = "Shared expenses and who pays whom. TripMates never holds money.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = LedgerController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "not_found: no such trip, or not one the caller travels on",
        content = @Content(mediaType = LedgerController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = LedgerController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class LedgerController {

    public static final String BASE = "/trips/{tripId}";

    static final String PROBLEM_JSON = "application/problem+json";

    private final LedgerService ledger;

    private final SettlementMapper mapper;

    public LedgerController(LedgerService ledger, SettlementMapper mapper) {
        this.ledger = ledger;
        this.mapper = mapper;
    }

    @GetMapping("/ledger")
    @Operation(summary = "The trip's settle-up screen", description = "Balances, the fewest payments that settle "
            + "them (with a UPI link for the payer), and recent expenses")
    @ApiResponse(responseCode = "200", description = "The ledger")
    public LedgerResponse ledger(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                 @PathVariable UUID tripId) {
        return mapper.toResponse(ledger.view(SessionPrincipal.of(token), tripId));
    }

    @GetMapping("/ledger/history")
    @Operation(summary = "Every transaction on the trip's ledger, newest first",
            description = "Expenses, their corrections and confirmed payments. Nothing is ever edited or removed.")
    @ApiResponse(responseCode = "200", description = "One page of the history")
    public PageResponse<LedgerHistoryItemResponse> history(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                           @PathVariable UUID tripId,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return mapper.toResponse(ledger.history(SessionPrincipal.of(token), tripId, Paging.of(page, size)));
    }

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record an expense the caller paid", description = "Split equally or in exact shares "
            + "among people on the trip. Retrying with the same clientExpenseId records it once.")
    @ApiResponse(responseCode = "201", description = "The ledger, with the expense and the new suggested payments")
    @ApiResponse(responseCode = "400", description = "invalid_description, invalid_amount, invalid_split, "
            + "participant_not_on_trip, split_needs_someone_else, or a malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "ledger_not_open, not_on_trip or expense_id_reused",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public LedgerResponse addExpense(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                     @PathVariable UUID tripId, @Valid @RequestBody NewExpenseRequest request) {
        long amount = ExpenseSplit.paise(request.amountPaise())
                .orElseThrow(() -> new ApiProblemException(SettlementProblem.INVALID_AMOUNT));
        var participants = request.participants().stream()
                .map(participant -> new ParticipantShare(participant.userId(), participant.sharePaise() == null
                        ? null
                        : ExpenseSplit.paise(participant.sharePaise())
                        .orElseThrow(() -> new ApiProblemException(SettlementProblem.INVALID_SPLIT))))
                .toList();
        return mapper.toResponse(ledger.addExpense(SessionPrincipal.of(token), tripId, new NewExpense(
                request.clientExpenseId(), request.description(), amount, request.split(), participants)));
    }
}
