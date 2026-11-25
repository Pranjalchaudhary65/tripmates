package com.tripmate.trips.dto;

import com.tripmate.trips.service.ConflictPremortem;
import com.tripmate.trips.service.PremortemService;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PremortemMapper {

    PremortemResponse toResponse(PremortemService.Premortem premortem);

    PremortemResponse.Pair toResponse(PremortemService.Pair pair);

    PremortemResponse.Topic toResponse(ConflictPremortem.Topic topic);
}
