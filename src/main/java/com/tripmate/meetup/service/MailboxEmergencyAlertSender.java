package com.tripmate.meetup.service;

import com.tripmate.identity.phone.PhoneNumbers;
import com.tripmate.identity.service.EmergencyContactService.AlertContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The demo's alert delivery, like the phone code "mailbox" sender: instead of
 * an SMS to the emergency contact, the account's own inbox gets the exact
 * text the contact would receive, with their number masked. It proves the
 * escalation fired without an SMS account. Logged once at startup as not for
 * real use.
 */
@Component
@ConditionalOnProperty(name = "tripmate.meetup.alert-sender", havingValue = MailboxEmergencyAlertSender.ID)
public class MailboxEmergencyAlertSender implements EmergencyAlertSender {

    public static final String ID = "mailbox";

    static final String SUBJECT = "TripMates: emergency contact alert (demo delivery)";

    private static final Logger log = LoggerFactory.getLogger(MailboxEmergencyAlertSender.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    private final MeetupMailer mailer;

    public MailboxEmergencyAlertSender(MeetupMailer mailer) {
        this.mailer = mailer;
        log.warn("Emergency alerts are delivered to the user's own inbox (alert-sender=mailbox); "
                + "no emergency contact is actually reached");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void send(AlertContact contact, Alert alert) {
        String sms = """
                TripMates safety alert for %s: %s planned to meet someone at %s (%s) at %s and has not checked in \
                or answered us. Please try to reach them. If you cannot and are worried, call 112.""".formatted(
                contact.name(), alert.userFirstName(), alert.venueName(), alert.venueAddress(),
                TIME.format(alert.scheduledAt().atZone(DISPLAY_ZONE)));
        mailer.send(alert.userEmail(), SUBJECT, """
                This copy is sent to you because this demo has no SMS provider. In production the text below
                goes by SMS to your emergency contact, %s (%s).

                %s
                """.formatted(contact.name(), PhoneNumbers.mask(contact.phone()), sms));
    }
}
