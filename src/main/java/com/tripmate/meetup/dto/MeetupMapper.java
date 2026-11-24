package com.tripmate.meetup.dto;

import com.tripmate.meetup.service.DaylightRules;
import com.tripmate.meetup.service.MeetupViews;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/** Service views to responses, field by field. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MeetupMapper {

    MatchResponse toResponse(MeetupViews.MatchView view);

    MatchResponse.Meetup toResponse(MeetupViews.MeetupView view);

    MatchResponse.Venue toResponse(MeetupViews.VenueRef venue);

    VenueOptionsResponse.Window toResponse(DaylightRules.Window window);

    VenueOptionsResponse.Option toResponse(MeetupViews.VenueOption option);

    default VenueOptionsResponse toResponse(List<MeetupViews.VenueOption> options) {
        return new VenueOptionsResponse(options.stream().map(this::toResponse).toList());
    }

    CheckInResponse toResponse(MeetupViews.CheckInResult result);
}
