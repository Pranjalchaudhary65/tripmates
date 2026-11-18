package com.tripmate.identity.dto;

import com.tripmate.identity.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Copies only the fields the response names; the password hash and the rest of the row never leave the entity. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CurrentUserMapper {

    @Mapping(target = "verificationTier", source = "idVerificationTier")
    CurrentUserResponse toResponse(User user);
}
