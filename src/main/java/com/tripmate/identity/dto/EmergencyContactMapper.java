package com.tripmate.identity.dto;

import com.tripmate.identity.service.EmergencyContactService.OwnContact;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EmergencyContactMapper {

    EmergencyContactResponse.Contact toContact(OwnContact contact);

    default EmergencyContactResponse toResponse(OwnContact contact) {
        return new EmergencyContactResponse(contact == null ? null : toContact(contact));
    }
}
