package com.tripmate.safety.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from live trip mode. Every detail is a fixed sentence. */
public enum SafetyProblem implements ProblemType {

    TRIP_NOT_LOCKED(HttpStatus.CONFLICT, "trip_not_locked",
            "Live trip mode starts once the trip is locked, after meeting in person.", null),

    TRIP_OVER(HttpStatus.CONFLICT, "trip_over", "This trip has ended.", null),

    EMERGENCY_CONTACT_NEEDED(HttpStatus.CONFLICT, "emergency_contact_needed",
            "Add an emergency contact first. They are told only if you miss a check-in or press SOS.", null),

    NOT_SHARING(HttpStatus.CONFLICT, "not_sharing",
            "Turn on trip sharing first, so your check-ins have someone to reach.", null),

    INVALID_INTERVAL(HttpStatus.BAD_REQUEST, "invalid_check_in_interval",
            "Choose check-ins every 3, 6, 12 or 24 hours.", "checkInEveryHours"),

    INVALID_NOTE(HttpStatus.BAD_REQUEST, "invalid_note",
            "Keep the note for your emergency contact to 300 characters of plain text.", "note"),

    INVALID_LOCATION(HttpStatus.BAD_REQUEST, "invalid_location", "Your location could not be read. Try again.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    SafetyProblem(HttpStatus status, String code, String detail, String field) {
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
