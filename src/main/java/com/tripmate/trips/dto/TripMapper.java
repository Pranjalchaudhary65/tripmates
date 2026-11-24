package com.tripmate.trips.dto;

import com.tripmate.trips.entity.TripMember;
import com.tripmate.trips.repository.TripCards;
import com.tripmate.trips.service.JoinRequestService;
import com.tripmate.trips.service.MatchScorer;
import com.tripmate.trips.service.MatchService;
import com.tripmate.trips.service.TripService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Read models and entities to responses, field by field. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TripMapper {

    PersonResponse toResponse(TripCards.PersonCard person);

    TripRefResponse toResponse(TripCards.TripRef trip);

    TripSummaryResponse.MyRequest toResponse(TripCards.ViewerRequest request);

    /** A trip card with its match score, or null where there is none to show. */
    @Mapping(target = "hostedByMe", source = "card.hostedByViewer")
    @Mapping(target = "myRequest", source = "card.viewerRequest")
    @Mapping(target = "matchScore", source = "matchScore")
    TripSummaryResponse toResponse(TripCards.TripCard card, Integer matchScore);

    /** A card for a list that is not matched, such as the caller's own trips. */
    default TripSummaryResponse toResponse(TripCards.TripCard card) {
        return toResponse(card, null);
    }

    MatchResponse.Reason toResponse(MatchScorer.Reason reason);

    MatchResponse.Blocker toResponse(MatchService.Blocker blocker);

    MatchResponse toResponse(MatchService.MatchView match);

    default TripDetailResponse toResponse(TripService.TripView view) {
        TripCards.TripPage page = view.page();
        MatchResponse match = view.match() == null ? null : toResponse(view.match());
        return new TripDetailResponse(toResponse(page.card(), match == null ? null : match.score()),
                page.description(), page.radiusKm(), page.createdAt(), match);
    }

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
