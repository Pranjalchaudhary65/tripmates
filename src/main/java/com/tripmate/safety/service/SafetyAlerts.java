package com.tripmate.safety.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.identity.emergency.EmergencyAlertSender;
import com.tripmate.identity.service.AgePolicy;
import com.tripmate.identity.service.ContactDirectory;
import com.tripmate.identity.service.EmergencyContactService;
import com.tripmate.safety.entity.Checkin;
import com.tripmate.safety.entity.ItineraryShare;
import com.tripmate.safety.entity.SosEvent;
import com.tripmate.safety.repository.SafetyQueries;
import com.tripmate.trips.service.TripCrew;
import com.tripmate.trips.service.TripCrew.Crew;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Emergency contact alerts for live trips, through identity's one
 * {@link EmergencyAlertSender}. Each alert says who, where they are
 * travelling, with whom (first names), what happened, and the last place
 * they shared: the exact point, decrypted here and nowhere else. The text is
 * fixed wording around those facts and the traveller's own note.
 */
@Component
public class SafetyAlerts {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EmergencyAlertSender sender;

    private final EmergencyContactService emergencyContacts;

    private final ContactDirectory contacts;

    private final UserDataCipher cipher;

    private final TripCrew crews;

    private final SafetyQueries queries;

    public SafetyAlerts(EmergencyAlertSender sender, EmergencyContactService emergencyContacts,
                        ContactDirectory contacts, UserDataCipher cipher, TripCrew crews, SafetyQueries queries) {
        this.sender = sender;
        this.emergencyContacts = emergencyContacts;
        this.contacts = contacts;
        this.cipher = cipher;
        this.crews = crews;
        this.queries = queries;
    }

    /**
     * @return false when there is nobody to alert (no emergency contact, or
     * an account no longer active); throws when the sender failed, so the
     * caller can retry
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean sos(SosEvent sos, Optional<ItineraryShare> share) {
        String location = sos.getLocationCiphertext() == null
                ? "No location was shared."
                : locationSentence(sos.getUserId(), EncryptedField.SOS_LOCATION, Locations.sosContext(sos),
                sos.getLocationCiphertext(), sos.getTriggeredAt());
        return send(sos.getUserId(), sos.getTripId(), share, (name, trip) -> "%s pressed SOS on TripMates at %s, %s %s"
                .formatted(name, TIME.format(sos.getTriggeredAt().atZone(AgePolicy.LEGAL_ZONE)), trip, location));
    }

    /** As {@link #sos}, for a check-in nobody answered. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean missedCheckin(Checkin missed, Optional<Checkin> lastAnswered, ItineraryShare share) {
        String location = lastAnswered
                .map(last -> last.getLocationCiphertext() == null
                        ? "They last checked in at %s, without a location.".formatted(
                        TIME.format(last.getRespondedAt().atZone(AgePolicy.LEGAL_ZONE)))
                        : locationSentence(last.getUserId(), EncryptedField.CHECKIN_LOCATION,
                        Locations.checkinContext(last), last.getLocationCiphertext(), last.getRespondedAt()))
                .orElse("They have not checked in on this trip yet.");
        return send(missed.getUserId(), missed.getTripId(), Optional.of(share), (name, trip) ->
                "%s has not answered a safety check-in due at %s, %s %s".formatted(name,
                        TIME.format(missed.getScheduledAt().atZone(AgePolicy.LEGAL_ZONE)), trip, location));
    }

    private boolean send(UUID userId, UUID tripId, Optional<ItineraryShare> share, Situation situation) {
        var account = contacts.contactOf(userId);
        var contact = emergencyContacts.forAlert(userId);
        Crew crew = crews.of(tripId).orElse(null);
        if (account.isEmpty() || contact.isEmpty() || crew == null) {
            return false;
        }
        Map<UUID, String> names = queries.firstNames(crew.travellers());
        String name = names.getOrDefault(userId, "Your TripMates contact");
        List<String> others = crew.travellers().stream().filter(id -> !id.equals(userId))
                .map(id -> names.getOrDefault(id, "a traveller")).toList();
        String trip = "while travelling to %s (%s to %s)%s.".formatted(crew.destinationName(),
                DATE.format(crew.startDate()), DATE.format(crew.endDate()),
                others.isEmpty() ? "" : " with " + String.join(" and ", others));
        String note = share.map(ItineraryShare::getNoteCiphertext)
                .flatMap(sealed -> cipher.open(userId, EncryptedField.ITINERARY_NOTE, Locations.noteContext(tripId),
                        sealed))
                .map(bytes -> " Their note: " + new String(bytes, StandardCharsets.UTF_8))
                .orElse("");
        sender.send(contact.get(), new EmergencyAlertSender.Alert(name, account.get().email(),
                situation.describe(name, trip) + note));
        return true;
    }

    private String locationSentence(UUID userId, EncryptedField field, String context, byte[] sealed, Instant at) {
        return cipher.open(userId, field, context, sealed)
                .map(bytes -> Locations.Exact.parse(new String(bytes, StandardCharsets.UTF_8)))
                .map(exact -> "Location at %s: https://maps.google.com/?q=%s,%s%s.".formatted(
                        TIME.format(at.atZone(AgePolicy.LEGAL_ZONE)), exact.latitude(), exact.longitude(),
                        exact.accuracyMeters() == null ? "" : " (within about %d m)".formatted(
                                Math.round(exact.accuracyMeters()))))
                .orElse("Their shared location can no longer be read.");
    }

    @FunctionalInterface
    private interface Situation {
        String describe(String firstName, String tripClause);
    }
}
