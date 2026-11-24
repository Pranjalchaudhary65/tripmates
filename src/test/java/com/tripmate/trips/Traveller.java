package com.tripmate.trips;

import com.tripmate.identity.RegistrationApi;
import com.tripmate.identity.SessionApi;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;

/** A verified, signed-in account on its own client IP, with the trip routes at hand. */
public record Traveller(LocalHttp http, String email, SessionApi.Session session, TripApi trips) {

    public static Traveller signUp(int port, MailpitClient mailpit) {
        LocalHttp http = new LocalHttp(port);
        String email = RegistrationApi.uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        return new Traveller(http, email, new SessionApi(http).signIn(email, STRONG_PASSWORD), new TripApi(http));
    }

    public String token() {
        return session.accessToken();
    }

    public String userId() {
        return session.userId();
    }
}
