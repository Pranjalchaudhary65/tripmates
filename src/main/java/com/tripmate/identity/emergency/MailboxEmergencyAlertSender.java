package com.tripmate.identity.emergency;

import com.tripmate.identity.mail.MailProperties;
import com.tripmate.identity.phone.PhoneNumbers;
import com.tripmate.identity.service.EmergencyContactService.AlertContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * The demo's alert delivery, like the phone code "mailbox" sender: instead of
 * an SMS to the emergency contact, the account's own inbox gets the exact
 * text the contact would receive, with their number masked. It proves the
 * escalation fired without an SMS account. Logged once at startup as not for
 * real use.
 */
@Component
@ConditionalOnProperty(name = "tripmate.identity.emergency-alert-sender", havingValue = MailboxEmergencyAlertSender.ID)
public class MailboxEmergencyAlertSender implements EmergencyAlertSender {

    public static final String ID = "mailbox";

    public static final String SUBJECT = "TripMates: emergency contact alert (demo delivery)";

    private static final Logger log = LoggerFactory.getLogger(MailboxEmergencyAlertSender.class);

    private final JavaMailSender mailSender;

    private final MailProperties properties;

    public MailboxEmergencyAlertSender(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
        log.warn("Emergency alerts are delivered to the user's own inbox (emergency-alert-sender=mailbox); "
                + "no emergency contact is actually reached");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void send(AlertContact contact, Alert alert) {
        String sms = """
                TripMates safety alert for %s: %s Please try to reach %s. If you cannot and are worried, call 112."""
                .formatted(contact.name(), alert.situation(), alert.userFirstName());
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(alert.userEmail());
        message.setSubject(SUBJECT);
        message.setText("""
                This copy is sent to you because this demo has no SMS provider. In production the text below
                goes by SMS to your emergency contact, %s (%s).

                %s
                """.formatted(contact.name(), PhoneNumbers.mask(contact.phone()), sms));
        mailSender.send(message);
    }
}
