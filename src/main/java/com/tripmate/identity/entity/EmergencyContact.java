package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A user's emergency contact (emergency_contacts). One per user; the phone is ciphertext only. */
@Entity
@Table(name = "emergency_contacts")
public class EmergencyContact {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Relationship relationship;

    @Column(name = "phone_ciphertext", nullable = false)
    private byte[] phoneCiphertext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmergencyContact() {
    }

    public static EmergencyContact create(UUID userId, String name, Relationship relationship,
                                          byte[] phoneCiphertext, Instant now) {
        EmergencyContact contact = new EmergencyContact();
        contact.userId = userId;
        contact.createdAt = now;
        contact.replace(name, relationship, phoneCiphertext, now);
        return contact;
    }

    public void replace(String name, Relationship relationship, byte[] phoneCiphertext, Instant now) {
        this.name = name;
        this.relationship = relationship;
        this.phoneCiphertext = phoneCiphertext;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public byte[] getPhoneCiphertext() {
        return phoneCiphertext;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Values match ck_emergency_contacts_relationship. */
    public enum Relationship {
        PARENT, SIBLING, PARTNER, RELATIVE, FRIEND, OTHER
    }
}
