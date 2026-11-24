package com.tripmate.meetup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.ZoneId;
import java.util.UUID;

/**
 * One place on the curated allowlist (DESIGN.md §5 verified_venues). Read
 * only: venues are seeded and curated by the platform, never written by a
 * user request.
 */
@Entity
@Immutable
@Table(name = "verified_venues")
public class Venue {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VenueCategory category;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(name = "time_zone", nullable = false, length = 40)
    private String timeZone;

    @Column(nullable = false)
    private boolean active;

    protected Venue() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getCity() {
        return city;
    }

    public VenueCategory getCategory() {
        return category;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public ZoneId getZone() {
        return ZoneId.of(timeZone);
    }

    public boolean isActive() {
        return active;
    }
}
