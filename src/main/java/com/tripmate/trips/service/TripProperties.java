package com.tripmate.trips.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * @param createLimit        trips opened, per account
 * @param joinRequestLimit   join requests sent, per account
 * @param decisionLimit      accepts and rejects, per account
 * @param readLimit          browse, detail and inbox reads, per account; a
 *                           browse runs a spatial query, so it is not free
 * @param maxCurrentTrips    listed trips one host may have that are not over yet
 * @param maxPendingRequests requests one account may have waiting at once
 * @param slotClaimAttempts  tries at an accept that keeps losing the trip's
 *                           optimistic lock before answering trip_busy
 */
@ConfigurationProperties("tripmate.trips")
public record TripProperties(RateLimit createLimit, RateLimit joinRequestLimit, RateLimit decisionLimit,
                             RateLimit readLimit, int maxCurrentTrips, int maxPendingRequests,
                             int slotClaimAttempts) {

    public TripProperties {
        Objects.requireNonNull(createLimit, "tripmate.trips.create-limit is required");
        Objects.requireNonNull(joinRequestLimit, "tripmate.trips.join-request-limit is required");
        Objects.requireNonNull(decisionLimit, "tripmate.trips.decision-limit is required");
        Objects.requireNonNull(readLimit, "tripmate.trips.read-limit is required");
        if (maxCurrentTrips < 1 || maxPendingRequests < 1 || slotClaimAttempts < 1) {
            throw new IllegalArgumentException(
                    "tripmate.trips max-current-trips, max-pending-requests and slot-claim-attempts must be positive");
        }
    }
}
