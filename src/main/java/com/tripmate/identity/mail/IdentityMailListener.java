package com.tripmate.identity.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends identity emails after the transaction that triggered them commits,
 * on a background thread.
 *
 * <p>After commit: a rolled-back registration never emails a code for a row
 * that does not exist. Background thread: SMTP latency (or an SMTP outage)
 * never reaches the HTTP response, so the time a register call takes does
 * not depend on which email - a code or an "already registered" notice - is
 * being sent, and cannot be used to tell the two apart.
 *
 * <p>A failed send is logged and dropped. The client already has its answer,
 * and the user recovers through "resend code".
 */
@Component
public class IdentityMailListener {

    private static final Logger log = LoggerFactory.getLogger(IdentityMailListener.class);

    private static final int MAX_CAUSE_DEPTH = 8;

    private final IdentityMailer mailer;

    public IdentityMailListener(IdentityMailer mailer) {
        this.mailer = mailer;
    }

    @Async
    @TransactionalEventListener
    public void on(VerificationCodeIssuedEvent event) {
        try {
            mailer.sendVerificationCode(event);
        } catch (MailException e) {
            log.warn("Verification email could not be sent: {}", causeChain(e));
        }
    }

    @Async
    @TransactionalEventListener
    public void on(ExistingAccountRegistrationEvent event) {
        try {
            mailer.sendExistingAccountNotice(event);
        } catch (MailException e) {
            log.warn("Existing-account notice could not be sent: {}", causeChain(e));
        }
    }

    /**
     * Exception class names only, outermost first, e.g.
     * {@code MailSendException > MessagingException > ConnectException}.
     * Enough to tell a refused connection from a timeout or an auth failure,
     * without the messages, which can quote recipient addresses.
     */
    static String causeChain(MailException e) {
        List<Throwable> roots = new ArrayList<>();
        roots.add(e);
        if (e instanceof MailSendException send) {
            roots.addAll(List.of(send.getMessageExceptions()));
        }
        StringBuilder chain = new StringBuilder();
        for (Throwable root : roots) {
            int depth = 0;
            for (Throwable t = root; t != null && depth++ < MAX_CAUSE_DEPTH; t = t.getCause()) {
                if (!chain.isEmpty()) {
                    chain.append(" > ");
                }
                chain.append(t.getClass().getSimpleName());
            }
        }
        return chain.toString();
    }
}
