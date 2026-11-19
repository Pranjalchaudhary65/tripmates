package com.tripmate.identity.mail;

import com.tripmate.identity.service.AgePolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Plain-text identity emails over SMTP. Plain text on purpose: there is no
 * HTML for a user-supplied name to be injected into, and the code is easy to
 * copy on any mail client.
 */
@Component
@EnableConfigurationProperties(MailProperties.class)
public class IdentityMailer {

    static final String VERIFICATION_SUBJECT = "Your TripMates verification code";

    static final String EXISTING_ACCOUNT_SUBJECT = "Someone tried to sign up with your email";

    static final String NEW_DEVICE_SUBJECT = "New sign-in to your TripMates account";

    static final String TWO_FACTOR_ENABLED_SUBJECT = "Two-factor authentication is now on for your TripMates account";

    static final String TWO_FACTOR_DISABLED_SUBJECT = "Two-factor authentication was turned off on your TripMates account";

    static final String BACKUP_CODES_REGENERATED_SUBJECT = "New TripMates backup codes were created";

    static final String BACKUP_CODE_USED_SUBJECT = "A backup code was used to sign in to TripMates";

    private static final String NOT_YOU = """
            If this was not you, someone has your password and access to your account. \
            Sign in, change your password, and use "sign out of all devices".""";

    /** Every TripMates user is told times in the zone the 18+ rule uses. */
    private static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("d MMM uuuu, HH:mm 'IST'", Locale.ENGLISH)
            .withZone(AgePolicy.LEGAL_ZONE);

    private final JavaMailSender mailSender;

    private final MailProperties properties;

    public IdentityMailer(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void sendVerificationCode(VerificationCodeIssuedEvent event) {
        long minutes = event.validFor().toMinutes();
        send(event.email(), VERIFICATION_SUBJECT, """
                Hi %s,

                Your TripMates verification code is: %s

                It expires in %d minutes. Enter it on the sign-up screen to verify your email address.

                If you did not sign up for TripMates, ignore this email. No account is activated without this code.
                """.formatted(event.fullName(), event.code(), minutes));
    }

    public void sendExistingAccountNotice(ExistingAccountRegistrationEvent event) {
        send(event.email(), EXISTING_ACCOUNT_SUBJECT, """
                Hi,

                Someone just tried to create a TripMates account with this email address, which already has an account.

                If that was you, log in with your existing password instead of signing up again.

                If it was not you, you can ignore this email. Nothing about your account has changed.
                """);
    }

    /**
     * The browser's User-Agent is deliberately left out: it is whatever the
     * person signing in chose to send, and if that person is an attacker it
     * would put their text into the victim's inbox under our name.
     */
    public void sendNewDeviceAlert(NewDeviceLoginEvent event) {
        send(event.email(), NEW_DEVICE_SUBJECT, """
                Hi %s,

                Your TripMates account was just signed in to from a browser or device that has not been used with it before.

                When: %s
                IP address: %s

                If this was you, there is nothing to do.

                If it was not you, someone else knows your password. Change it on every site where you use it.
                """.formatted(event.fullName(), WHEN.format(event.at()), event.ipAddress()));
    }

    /**
     * One email per kind of change, sent whoever made it: if it was an
     * attacker, this is how the owner finds out. Never carries a code, a
     * secret or a backup code.
     */
    public void sendTwoFactorNotice(TwoFactorNoticeEvent event) {
        String when = WHEN.format(event.at());
        String subject;
        String what;
        switch (event.kind()) {
            case ENABLED -> {
                subject = TWO_FACTOR_ENABLED_SUBJECT;
                what = "Two-factor authentication was turned on. Signing in now needs a code from your "
                        + "authenticator app as well as your password, and every other session was signed out.";
            }
            case DISABLED -> {
                subject = TWO_FACTOR_DISABLED_SUBJECT;
                what = "Two-factor authentication was turned off. Your password alone now signs in to your "
                        + "account, and every other session was signed out.";
            }
            case BACKUP_CODES_REGENERATED -> {
                subject = BACKUP_CODES_REGENERATED_SUBJECT;
                what = "A new set of backup codes was created. Your old backup codes no longer work.";
            }
            case BACKUP_CODE_USED -> {
                subject = BACKUP_CODE_USED_SUBJECT;
                what = "A backup code was used instead of your authenticator app to sign in. That code no "
                        + "longer works. Backup codes left: " + event.backupCodesLeft() + ".";
            }
            default -> throw new IllegalArgumentException("Unknown two-factor notice " + event.kind());
        }
        send(event.email(), subject, """
                Hi %s,

                %s

                When: %s
                IP address: %s

                %s
                """.formatted(event.fullName(), what, when, event.ipAddress(), NOT_YOU));
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
