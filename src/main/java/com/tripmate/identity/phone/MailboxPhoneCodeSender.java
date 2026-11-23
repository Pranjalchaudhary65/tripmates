package com.tripmate.identity.phone;

import com.tripmate.identity.mail.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * {@code tripmate.identity.phone.sms-provider=mailbox}: instead of an SMS,
 * the code goes to the account's own email inbox, marked as the text message
 * it stands in for. It lets the phone tier be demonstrated with no SMS
 * account or API key.
 *
 * <p>It proves control of the inbox, not of the phone, so the PHONE tier it
 * grants is only as strong as the EMAIL tier. Startup says so; a deployment
 * that relies on the tier sets a real provider.
 */
@Component
@ConditionalOnProperty(prefix = "tripmate.identity.phone", name = "sms-provider", havingValue = "mailbox")
@EnableConfigurationProperties(MailProperties.class)
class MailboxPhoneCodeSender implements PhoneCodeSender {

    static final String SUBJECT = "Your TripMates phone verification code";

    private static final Logger log = LoggerFactory.getLogger(MailboxPhoneCodeSender.class);

    private final JavaMailSender mailSender;

    private final MailProperties properties;

    MailboxPhoneCodeSender(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
        log.warn("Phone codes are delivered to the account's email inbox (sms-provider=mailbox); "
                + "the PHONE tier does not prove possession of a phone");
    }

    @Override
    public void send(PhoneCodeIssuedEvent message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.from());
        mail.setTo(message.email());
        mail.setSubject(SUBJECT);
        mail.setText("""
                Hi %s,

                This email stands in for the text message TripMates would send to %s.

                Your TripMates phone verification code is: %s

                It expires in %d minutes. If you did not ask to add this phone number, ignore this email.
                """.formatted(message.fullName(), PhoneNumbers.mask(message.e164()), message.code(),
                message.validFor().toMinutes()));
        mailSender.send(mail);
    }
}
