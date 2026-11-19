package com.tripmate.identity.dto;

import com.tripmate.identity.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Field by field; an unmapped response field fails the build, and nothing else on the entity is copied. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AdminUserMapper {

    @Mapping(target = "verificationTier", source = "idVerificationTier")
    @Mapping(target = "twoFactorEnabled", source = "totpEnabled")
    AdminUserResponse toResponse(User user);
}
