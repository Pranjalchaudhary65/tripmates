package com.tripmate.trips.repository;

import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.TripStatus;
import com.tripmate.vibe.entity.BudgetBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read models for the trip screens, built straight from SQL. No user ID of
 * anyone but the caller appears in any of them: another person is only ever
 * a first name and a verification tier.
 */
public final class TripCards {

    private TripCards() {
    }

    /** Someone else on a trip, as much as a stranger may see before the trust ladder has gone anywhere. */
    public record PersonCard(String firstName, VerificationTier verificationTier) {
    }

    /** The trip a join request is about. */
    public record TripRef(UUID id, String title, String destinationName, LocalDate startDate, LocalDate endDate,
                          TripStatus status, int totalSlots, int filledSlots) {
    }

    /**
     * One trip as a list shows it, seen by one viewer.
     *
     * @param distanceKm      from the point the viewer searched around; null without one
     * @param viewerRequest   the viewer's own join request on this trip, if any
     * @param pendingRequests requests waiting for a decision; only for the trip's host, otherwise null
     */
    public record TripCard(UUID id, String title, String destinationName, double latitude, double longitude,
                           LocalDate startDate, LocalDate endDate, int totalSlots, int filledSlots,
                           BudgetBand budgetBand, TripStatus status, Double distanceKm, PersonCard host,
                           boolean hostedByViewer, ViewerRequest viewerRequest, Integer pendingRequests) {
    }

    /** The viewer's own request on a trip. */
    public record ViewerRequest(UUID id, MemberStatus status) {
    }

    /**
     * A trip card with what only the detail screen shows.
     *
     * @param hostUserId  for the service's visibility and matching decisions; never returned to a client
     * @param hostActive  false once the host's account is suspended, banned or deleted
     */
    public record TripPage(TripCard card, String description, BigDecimal radiusKm, Instant createdAt,
                           UUID hostUserId, boolean hostActive) {
    }

    /** A request on one of the viewer's trips, for the host's inbox. */
    public record IncomingRequest(UUID id, MemberStatus status, Instant requestedAt, Instant decidedAt,
                                  TripRef trip, PersonCard requester) {
    }

    /** One of the viewer's own requests, with its trip and host. */
    public record OutgoingRequest(UUID id, MemberStatus status, Instant requestedAt, Instant decidedAt,
                                  TripRef trip, PersonCard host) {
    }
}
