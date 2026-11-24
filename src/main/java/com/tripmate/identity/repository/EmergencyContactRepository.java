package com.tripmate.identity.repository;

import com.tripmate.identity.entity.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, UUID> {
}
