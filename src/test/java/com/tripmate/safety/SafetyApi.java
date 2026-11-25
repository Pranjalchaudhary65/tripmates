package com.tripmate.safety;

import com.tripmate.support.LocalHttp;

import java.net.http.HttpResponse;
import java.util.UUID;

/** Calls to the live trip, SOS and pre-mortem routes. */
public final class SafetyApi {

    private final LocalHttp http;

    public SafetyApi(LocalHttp http) {
        this.http = http;
    }

    public HttpResponse<String> live(String token, String tripId) {
        return http.get("/api/v1/trips/" + tripId + "/live", bearer(token));
    }

    public static String sharingJson(int everyHours, String note) {
        return note == null ? "{\"checkInEveryHours\":%d}".formatted(everyHours)
                : "{\"checkInEveryHours\":%d,\"note\":\"%s\"}".formatted(everyHours, note);
    }

    public HttpResponse<String> share(String token, String tripId, int everyHours, String note) {
        return http.putJson("/api/v1/trips/" + tripId + "/live/sharing", sharingJson(everyHours, note), bearer(token));
    }

    public HttpResponse<String> stopSharing(String token, String tripId) {
        return http.delete("/api/v1/trips/" + tripId + "/live/sharing", bearer(token));
    }

    public static String locationJson(Double latitude, Double longitude, Double accuracy) {
        return latitude == null ? "{}"
                : "{\"latitude\":%s,\"longitude\":%s,\"accuracyMeters\":%s}".formatted(latitude, longitude, accuracy);
    }

    public HttpResponse<String> checkIn(String token, String tripId, Double latitude, Double longitude) {
        return http.postJson("/api/v1/trips/" + tripId + "/live/check-in", locationJson(latitude, longitude, 25.0),
                bearer(token));
    }

    public static String sosJson(UUID clientSosId, Double latitude, Double longitude) {
        return latitude == null ? "{\"clientSosId\":\"%s\"}".formatted(clientSosId)
                : "{\"clientSosId\":\"%s\",\"latitude\":%s,\"longitude\":%s,\"accuracyMeters\":12}"
                .formatted(clientSosId, latitude, longitude);
    }

    public HttpResponse<String> sos(String token, String tripId, UUID clientSosId, Double latitude, Double longitude) {
        return http.postJson("/api/v1/trips/" + tripId + "/sos", sosJson(clientSosId, latitude, longitude),
                bearer(token));
    }

    public HttpResponse<String> resolve(String token, String sosId) {
        return http.post("/api/v1/sos/" + sosId + "/resolve", bearer(token));
    }

    public HttpResponse<String> premortem(String token, String tripId) {
        return http.get("/api/v1/trips/" + tripId + "/premortem", bearer(token));
    }

    private static String[] bearer(String token) {
        return new String[] {"Authorization", "Bearer " + token};
    }
}
