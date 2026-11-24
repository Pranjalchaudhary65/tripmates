package com.tripmate.identity.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.EmergencyContact;
import com.tripmate.identity.entity.EmergencyContact.Relationship;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.phone.PhoneNumbers;
import com.tripmate.identity.repository.EmergencyContactRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.ProblemType;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The caller's emergency contact: the one person alerted if they go quiet at
 * a meet-up. Set, read and removed only on the caller's own account. The
 * number is stored encrypted and shown back to its owner masked; the whole
 * number is decrypted only for an alert, by the module that sends it.
 */
@Service
public class EmergencyContactService {

    static final String BUCKET = "emergency-contact";

    private final UserRepository users;

    private final EmergencyContactRepository contacts;

    private final FieldEncryption encryption;

    private final RateLimiter rateLimiter;

    private final VerificationLimitProperties limits;

    private final Clock clock;

    public EmergencyContactService(UserRepository users, EmergencyContactRepository contacts,
                                   FieldEncryption encryption, RateLimiter rateLimiter,
                                   VerificationLimitProperties limits, Clock clock) {
        this.users = users;
        this.contacts = contacts;
        this.encryption = encryption;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<OwnContact> mine(SessionPrincipal caller) {
        User user = activeUser(caller);
        return contacts.findById(user.getId()).map(contact -> ownView(user, contact));
    }

    /**
     * Sets or replaces the contact. Refuses the caller's own verified number:
     * an alert that goes to the person who went quiet helps nobody.
     */
    @Transactional
    public OwnContact set(SessionPrincipal caller, String name, Relationship relationship, String phoneNumber) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), limits.emergencyContact());
        String e164 = PhoneNumbers.normalise(phoneNumber)
                .orElseThrow(() -> new ApiProblemException(VerificationProblem.INVALID_PHONE_NUMBER));
        User user = users.findByIdForUpdate(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        if (user.isPhoneVerified() && e164.equals(phoneOf(user))) {
            throw new ApiProblemException(Problem.OWN_NUMBER);
        }
        byte[] ciphertext = encryption.encrypt(user, EncryptedField.EMERGENCY_CONTACT_PHONE,
                e164.getBytes(StandardCharsets.UTF_8));
        String cleanName = name.strip();
        EmergencyContact contact = contacts.findById(user.getId())
                .map(existing -> {
                    existing.replace(cleanName, relationship, ciphertext, clock.instant());
                    return existing;
                })
                .orElseGet(() -> EmergencyContact.create(user.getId(), cleanName, relationship, ciphertext,
                        clock.instant()));
        contacts.save(contact);
        return new OwnContact(contact.getName(), contact.getRelationship(), PhoneNumbers.mask(e164),
                contact.getUpdatedAt());
    }

    @Transactional
    public void remove(SessionPrincipal caller) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), limits.emergencyContact());
        User user = users.findByIdForUpdate(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        contacts.findById(user.getId()).ifPresent(contacts::delete);
    }

    /** For other modules deciding whether someone may arrange a meet-up: no data, just yes or no. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasContact(UUID userId) {
        return contacts.existsById(userId);
    }

    /**
     * The whole contact, number in clear, for the module sending an alert.
     * Empty when there is none, or when it can no longer be read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AlertContact> forAlert(UUID userId) {
        return contacts.findById(userId).flatMap(contact -> users.findById(userId).flatMap(user ->
                encryption.tryDecrypt(user, EncryptedField.EMERGENCY_CONTACT_PHONE, null,
                                contact.getPhoneCiphertext())
                        .map(phone -> new AlertContact(contact.getName(), contact.getRelationship(),
                                new String(phone, StandardCharsets.UTF_8)))));
    }

    private OwnContact ownView(User user, EmergencyContact contact) {
        String masked = encryption.tryDecrypt(user, EncryptedField.EMERGENCY_CONTACT_PHONE, null,
                        contact.getPhoneCiphertext())
                .map(phone -> PhoneNumbers.mask(new String(phone, StandardCharsets.UTF_8)))
                .orElse(null);
        return new OwnContact(contact.getName(), contact.getRelationship(), masked, contact.getUpdatedAt());
    }

    private String phoneOf(User user) {
        return new String(encryption.decrypt(user, EncryptedField.PHONE, user.getPhoneCiphertext()),
                StandardCharsets.UTF_8);
    }

    private User activeUser(SessionPrincipal caller) {
        return users.findById(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
    }

    /** @param maskedPhone null when the stored number can no longer be read */
    public record OwnContact(String name, Relationship relationship, String maskedPhone,
                             java.time.Instant updatedAt) {
    }

    /** @param phone E.164 */
    public record AlertContact(String name, Relationship relationship, String phone) {
    }

    enum Problem implements ProblemType {

        OWN_NUMBER("emergency_contact_is_you",
                "Your emergency contact must be someone else. Enter their number, not yours.");

        private final String code;

        private final String detail;

        Problem(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.BAD_REQUEST;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String detail() {
            return detail;
        }

        @Override
        public String field() {
            return "phoneNumber";
        }
    }
}
