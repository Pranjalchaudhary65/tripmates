package com.tripmate.identity.crypto;

import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Field encryption under a user's data key for other modules, addressed by
 * user ID so no module outside identity handles a {@link User}. Everything
 * other modules seal this way is erased with the user: crypto-shredding
 * their data key makes it unreadable wherever it is stored.
 */
@Service
public class UserDataCipher {

    private final UserRepository users;

    private final FieldEncryption encryption;

    public UserDataCipher(UserRepository users, FieldEncryption encryption) {
        this.users = users;
        this.encryption = encryption;
    }

    /**
     * The caller holds the user's row lock (for example through
     * {@code AccountService.lockActive}), because sealing the first value of
     * a user creates their data key.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public byte[] seal(UUID userId, EncryptedField field, String context, byte[] plaintext) {
        User user = users.findById(userId).orElseThrow(() -> new IllegalStateException("No such user"));
        return encryption.encrypt(user, field, context, plaintext);
    }

    /**
     * Empty when the value can no longer be read: its owner's key has been
     * shredded, or the ciphertext was altered or moved to another row. The
     * caller shows that as "no longer available" rather than failing the
     * whole read over one value.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<byte[]> open(UUID userId, EncryptedField field, String context, byte[] ciphertext) {
        return users.findById(userId).flatMap(user -> encryption.tryDecrypt(user, field, context, ciphertext));
    }
}
