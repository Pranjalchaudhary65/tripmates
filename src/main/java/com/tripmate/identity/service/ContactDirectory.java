package com.tripmate.identity.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Real contact details of a user, for the one place another traveller may
 * see them: a conversation whose trip has locked. Identity does not decide
 * when that is; the calling module does, and this only reads. No endpoint
 * of identity's own calls it.
 */
@Service
public class ContactDirectory {

    private final UserRepository users;

    private final FieldEncryption encryption;

    public ContactDirectory(UserRepository users, FieldEncryption encryption) {
        this.users = users;
        this.encryption = encryption;
    }

    /**
     * Empty when the account is no longer active. The phone number is given
     * only once it has been verified; a number typed but never confirmed
     * may not be the user's at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Contact> contactOf(UUID userId) {
        return users.findById(userId).filter(User::isActive).map(user -> new Contact(
                user.getFullName(), user.getEmail(),
                user.isPhoneVerified() && user.getPhoneCiphertext() != null
                        ? new String(encryption.decrypt(user, EncryptedField.PHONE, user.getPhoneCiphertext()),
                        StandardCharsets.UTF_8)
                        : null));
    }

    /** @param phone E.164, or null when no number has been verified */
    public record Contact(String fullName, String email, String phone) {
    }
}
