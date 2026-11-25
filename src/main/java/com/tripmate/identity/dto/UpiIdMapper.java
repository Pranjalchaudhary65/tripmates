package com.tripmate.identity.dto;

import com.tripmate.identity.service.UpiIdService.OwnUpiId;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UpiIdMapper {

    UpiIdResponse.UpiId toUpiId(OwnUpiId upiId);

    default UpiIdResponse toResponse(OwnUpiId upiId) {
        return new UpiIdResponse(upiId == null ? null : toUpiId(upiId));
    }
}
