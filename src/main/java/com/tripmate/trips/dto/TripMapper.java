package com.tripmate.trips.dto;

import com.tripmate.trips.entity.TripMember;
import com.tripmate.trips.repository.TripCards;
import com.tripmate.trips.service.JoinRequestService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Read models and entities to responses, field by field. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TripMapper {

    PersonResponse toResponse(TripCards.PersonCard person);

    TripRefResponse toResponse(TripCards.TripRef trip);

    TripSummaryResponse.MyRequest toResponse(TripCards.ViewerRequest request);

    @Mapping(target = "hostedByMe", source = "hostedByViewer")
    @Mapping(target = "myRequest", source = "viewerRequest")
    TripSummaryResponse toResponse(TripCards.TripCard card);

    @Mapping(target = "trip", source = "card")
    TripDetailResponse toResponse(TripCards.TripPage page);

    IncomingRequestResponse toResponse(TripCards.IncomingRequest request);

    OutgoingRequestResponse toResponse(TripCards.OutgoingRequest request);

    JoinRequestCreatedResponse toCreatedResponse(TripMember request);

    default JoinDecisionResponse toResponse(JoinRequestService.Decision decision) {
        TripMember request = decision.request();
        return new JoinDecisionResponse(request.getId(), request.getStatus(), request.getDecidedAt(),
                new JoinDecisionResponse.Trip(decision.trip().getId(), decision.trip().getStatus(),
                        decision.trip().getTotalSlots(), decision.trip().getFilledSlots()));
    }
}
