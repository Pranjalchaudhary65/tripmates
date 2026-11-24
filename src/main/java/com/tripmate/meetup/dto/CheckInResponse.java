package com.tripmate.meetup.dto;

/** @param meetupCompleted both people have now checked in */
public record CheckInResponse(boolean arrived, boolean meetupCompleted) {
}
