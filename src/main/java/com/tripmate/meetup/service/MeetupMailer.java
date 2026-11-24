package com.tripmate.meetup.service;

import com.tripmate.identity.mail.MailProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Meet-up safety emails. Plain text, fixed wording; the only things filled in
 * are a first name, a venue and a time, none of which a user typed into a
 * free-text field at the moment it is sent.
 */
@Component
public class MeetupMailer {

    static final String PING_SUBJECT = "TripMates: did you get to your meet-up?";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    private final JavaMailSender mailSender;

    private final MailProperties properties;

    public MeetupMailer(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** The 30-minute check: the person has neither checked in nor said they are safe. */
    public void sendPing(String email, String firstName, String venueName, Instant scheduledAt, ZoneId zone) {
        send(email, PING_SUBJECT, """
                Hi %s,

                Your meet-up at %s was due to start at %s and you have not checked in yet.

                Open TripMates and check in at the venue, or tap "I'm safe" if you are fine.
                If we do not hear from you within 30 minutes, we will alert your emergency contact.

                If you feel unsafe, leave, go somewhere busy, and call 112.
                """.formatted(firstName, venueName, TIME.format(scheduledAt.atZone(zone))));
    }

    void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
    }
}
