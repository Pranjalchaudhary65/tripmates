package com.tripmate.meetup.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from the meet-up. Every detail is a fixed sentence; none says anything about the other person. */
public enum MeetupProblem implements ProblemType {

    MATCH_CLOSED(HttpStatus.CONFLICT, "match_closed", "This match is no longer being arranged.", null),

    VIDEO_CALL_NEEDED(HttpStatus.CONFLICT, "video_call_needed",
            "You both need to confirm your video call before arranging a meet-up.", null),

    EMERGENCY_CONTACT_NEEDED(HttpStatus.CONFLICT, "emergency_contact_needed",
            "Add an emergency contact before arranging a meet-up. They are told only if you go quiet at it.", null),

    MEETUP_EXISTS(HttpStatus.CONFLICT, "meetup_exists",
            "A meet-up is already being arranged for this match.", null),

    NO_MEETUP(HttpStatus.CONFLICT, "no_meetup", "There is no meet-up being arranged for this match.", null),

    OWN_PROPOSAL(HttpStatus.CONFLICT, "own_proposal", "The other person needs to confirm your proposal.", null),

    MEETUP_PASSED(HttpStatus.CONFLICT, "meetup_passed",
            "This meet-up's time has passed. Propose a new one.", null),

    VENUE_UNAVAILABLE(HttpStatus.BAD_REQUEST, "venue_unavailable",
            "Pick a venue from the list. Only verified public places can be used.", "venueId"),

    NOT_IN_DAYLIGHT(HttpStatus.BAD_REQUEST, "meetup_not_in_daylight",
            "Meet-ups happen in daylight. Pick a start time inside the venue's daylight window.", "scheduledAt"),

    TIME_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "meetup_time_out_of_range",
            "Pick a time at least 3 hours from now, within 30 days, and before the trip starts.", "scheduledAt"),

    INVALID_LOCATION(HttpStatus.BAD_REQUEST, "invalid_location", "Your location could not be read. Try again.", null),

    LOCATION_TOO_ROUGH(HttpStatus.CONFLICT, "location_too_rough",
            "Your phone's location is too rough right now. Turn on precise location, step outside, and try again.",
            null),

    CHECK_IN_CLOSED(HttpStatus.CONFLICT, "check_in_closed",
            "Check-in opens 30 minutes before the meet-up and closes 90 minutes after it starts.", null),

    NOT_AT_VENUE(HttpStatus.CONFLICT, "not_at_venue",
            "You need to be at the venue to check in, within 100 metres of it.", null),

    ALREADY_CHECKED_IN(HttpStatus.CONFLICT, "already_checked_in", "You have already checked in.", null),

    FEEDBACK_NOT_OPEN(HttpStatus.CONFLICT, "feedback_not_open",
            "You can say how the meet-up felt once you have both checked in.", null),

    FEEDBACK_GIVEN(HttpStatus.CONFLICT, "feedback_given", "You have already told us how the meet-up felt.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    MeetupProblem(HttpStatus status, String code, String detail, String field) {
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
