package com.tripmate.safety.service;

import com.tripmate.identity.mail.MailProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** The "are you safe?" email. Plain text, fixed wording; only a first name and a trip title are filled in. */
@Component
public class SafetyMailer {

    static final String PING_SUBJECT = "TripMates: are you safe?";

    private final JavaMailSender mailSender;

    private final MailProperties properties;

    public SafetyMailer(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void sendCheckInPing(String email, String firstName, String tripTitle, long minutesToEscalation) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject(PING_SUBJECT);
        message.setText("""
                Hi %s,

                It is time for your safety check-in on "%s". Open TripMates and tap "I'm safe".

                If we do not hear from you within %d minutes, we will alert your emergency contact.

                If you feel unsafe, go somewhere busy, press SOS in TripMates, and call 112.
                """.formatted(firstName, tripTitle, minutesToEscalation));
        mailSender.send(message);
    }
}
