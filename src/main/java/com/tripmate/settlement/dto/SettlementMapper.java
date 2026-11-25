package com.tripmate.settlement.dto;

import com.tripmate.platform.web.PageResponse;
import com.tripmate.settlement.service.SettlementViews;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.data.domain.Page;

/** Service views to responses, field by field. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SettlementMapper {

    LedgerResponse toResponse(SettlementViews.LedgerView view);

    LedgerResponse.Trip toResponse(SettlementViews.TripRef trip);

    LedgerResponse.Member toResponse(SettlementViews.Member member);

    LedgerResponse.Person toResponse(SettlementViews.PersonRef person);

    LedgerResponse.Settlement toResponse(SettlementViews.SettlementView settlement);

    LedgerResponse.Payment toResponse(SettlementViews.Payment payment);

    LedgerResponse.Expense toResponse(SettlementViews.ExpenseView expense);

    LedgerResponse.Share toResponse(SettlementViews.Share share);

    LedgerHistoryItemResponse toResponse(SettlementViews.HistoryItem item);

    default PageResponse<LedgerHistoryItemResponse> toResponse(Page<SettlementViews.HistoryItem> page) {
        return PageResponse.of(page, this::toResponse);
    }
}
