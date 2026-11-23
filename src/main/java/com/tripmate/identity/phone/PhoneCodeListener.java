package com.tripmate.identity.phone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands a phone code to the sender after its transaction commits, on a
 * background thread: a rolled-back request never sends a code that does not
 * exist, and a slow gateway never holds up the response. A failed send is
 * logged and dropped; the user asks for a new code.
 */
@Component
class PhoneCodeListener {

    private static final Logger log = LoggerFactory.getLogger(PhoneCodeListener.class);

    private final PhoneCodeSender sender;

    PhoneCodeListener(PhoneCodeSender sender) {
        this.sender = sender;
    }

    @Async
    @TransactionalEventListener
    public void on(PhoneCodeIssuedEvent event) {
        try {
            sender.send(event);
        } catch (RuntimeException e) {
            log.warn("Phone verification code could not be sent: {}", e.getClass().getSimpleName());
        }
    }
}
