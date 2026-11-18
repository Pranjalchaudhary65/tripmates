package com.tripmate.identity.dto;

import com.tripmate.identity.entity.RefreshTokenFamily;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

/**
 * Entity to response, field by field. An unmapped response field fails the
 * build, so a field added to the DTO later cannot silently go out empty, and
 * entity fields such as {@code userAgent} are never copied unless named here.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ActiveSessionMapper {

    @Mapping(target = "device", source = "deviceName")
    @Mapping(target = "ipAddress", source = "lastIpAddress")
    @Mapping(target = "signedInAt", source = "createdAt")
    @Mapping(target = "lastActiveAt", source = "lastUsedAt")
    @Mapping(target = "current", expression = "java(family.getId().equals(currentSessionId))")
    ActiveSessionResponse toResponse(RefreshTokenFamily family, @Context UUID currentSessionId);

    List<ActiveSessionResponse> toResponses(List<RefreshTokenFamily> families, @Context UUID currentSessionId);

    default ActiveSessionListResponse toListResponse(List<RefreshTokenFamily> families, UUID currentSessionId) {
        return new ActiveSessionListResponse(toResponses(families, currentSessionId));
    }

    default String format(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }
}
