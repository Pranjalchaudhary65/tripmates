package com.tripmate.safety.dto;

import com.tripmate.safety.service.LiveTripViews;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Service views to responses, field by field. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SafetyMapper {

    LiveTripResponse toResponse(LiveTripViews.LiveTripView view);

    LiveTripResponse.Trip toResponse(LiveTripViews.TripRef trip);

    LiveTripResponse.Sharing toResponse(LiveTripViews.Sharing sharing);

    LiveTripResponse.NextCheckIn toResponse(LiveTripViews.NextCheckIn next);

    LiveTripResponse.LastCheckIn toResponse(LiveTripViews.LastCheckIn last);

    LiveTripResponse.Buddy toResponse(LiveTripViews.Buddy buddy);

    LiveTripResponse.OpenSos toResponse(LiveTripViews.OpenSos sos);
}
