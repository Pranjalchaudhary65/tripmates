package com.tripmate.trips.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from trips and join requests. Every detail is a fixed sentence; none echoes the request. */
public enum TripProblem implements ProblemType {

    INVALID_TRIP_DATES(HttpStatus.BAD_REQUEST, "invalid_trip_dates",
            "Pick a start date from today up to a year ahead, and an end date no more than 60 days after it.",
            "startDate"),

    INVALID_LOCATION_FILTER(HttpStatus.BAD_REQUEST, "invalid_location_filter",
            "To search near a place, give its latitude, longitude and a radius together.", "near"),

    INVALID_DATE_FILTER(HttpStatus.BAD_REQUEST, "invalid_date_filter",
            "The end of the date range cannot be before its start.", "to"),

    TOO_MANY_TRIPS(HttpStatus.CONFLICT, "too_many_trips",
            "You already host as many upcoming trips as you can. Finish or cancel one first.", null),

    OWN_TRIP(HttpStatus.CONFLICT, "own_trip", "You are hosting this trip.", null),

    JOIN_REQUEST_EXISTS(HttpStatus.CONFLICT, "join_request_exists",
            "You have already asked to join this trip.", null),

    TOO_MANY_PENDING_REQUESTS(HttpStatus.CONFLICT, "too_many_pending_requests",
            "You have too many requests waiting for an answer. Wait for some of them first.", null),

    TRIP_FULL(HttpStatus.CONFLICT, "trip_full", "This trip has no free places left.", null),

    TRIP_CLOSED(HttpStatus.CONFLICT, "trip_closed", "This trip is no longer taking companions.", null),

    /** A deal-breaker on one side or the other rules the trip out; which one is on the trip page. */
    DEAL_BREAKER_CONFLICT(HttpStatus.CONFLICT, "deal_breaker_conflict",
            "Your deal-breakers and this trip's host's do not fit together, so you cannot ask to join it.", null),

    JOIN_REQUEST_DECIDED(HttpStatus.CONFLICT, "join_request_decided",
            "This request has already been answered.", null),

    /** Every retry of a slot claim lost to another change on the same trip. */
    TRIP_BUSY(HttpStatus.CONFLICT, "trip_busy",
            "This trip changed while your answer was being saved. Try again.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    TripProblem(HttpStatus status, String code, String detail, String field) {
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.field = field;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public String field() {
        return field;
    }
}
