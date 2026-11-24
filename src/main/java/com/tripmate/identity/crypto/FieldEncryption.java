package com.tripmate.identity.crypto;

import com.tripmate.identity.entity.User;
import com.tripmate.identity.entity.UserEncryptionKey;
import com.tripmate.identity.repository.UserEncryptionKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Field-level envelope encryption, per DESIGN.md (user_encryption_keys).
 *
 * <p>Each user has one data key (DEK), created the first time one of their
 * fields is encrypted and stored only wrapped by the application KEK. A field
 * is sealed with AES-256-GCM under the DEK, with the column and user ID as
 * additional data. The DEK is unwrapped for each call and its bytes are
 * zeroed straight after; there is no cache, so shredding a key takes effect
 * at once, with nothing left in memory to purge.
 *
 * <p>Callers hold the user row lock, so two first-time encryptions for one
 * user cannot each create a key.
 */
@Service
public class FieldEncryption {

    private static final int DATA_KEY_BYTES = 32;

    private static final byte VERSION = 1;

    private final SecureRandom random = new SecureRandom();

    private final UserEncryptionKeyRepository keys;

    private final KeyEncryptionKey kek;

    private final Clock clock;

    public FieldEncryption(UserEncryptionKeyRepository keys, KeyEncryptionKey kek, Clock clock) {
        this.keys = keys;
        this.kek = kek;
        this.clock = clock;
    }

    /** Seals {@code plaintext} for {@code field} of the locked {@code user}, creating their data key if needed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public byte[] encrypt(User user, EncryptedField field, byte[] plaintext) {
        return encrypt(user, field, null, plaintext);
    }

    /**
     * Seals a value that belongs to one row among many of the same user, such
     * as one chat message. {@code context} names that row and joins the
     * additional data, so the ciphertext opens only for the row it was
     * written for, not for another row of the same user and column.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public byte[] encrypt(User user, EncryptedField field, String context, byte[] plaintext) {
        byte[] dataKey = user.getEncryptionKeyId() == null ? createDataKey(user) : unwrap(user);
        try {
            byte[] sealed = AesGcm.seal(dataKey, plaintext, additionalData(field, user.getId(), context));
            byte[] out = new byte[1 + sealed.length];
            out[0] = VERSION;
            System.arraycopy(sealed, 0, out, 1, sealed.length);
            return out;
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /**
     * @throws DecryptionFailedException when the ciphertext was altered or
     *         moved, or the user's data key has been shredded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public byte[] decrypt(User user, EncryptedField field, byte[] ciphertext) {
        return decrypt(user, field, null, ciphertext);
    }

    /**
     * Like {@link #decrypt(User, EncryptedField, String, byte[])}, but an
     * unreadable value is empty instead of an exception. A caller that shows
     * "no longer available" must use this one: an exception thrown out of a
     * transactional method marks the caller's whole transaction rollback-only,
     * even when the caller catches it, and the request would fail anyway.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<byte[]> tryDecrypt(User user, EncryptedField field, String context, byte[] ciphertext) {
        try {
            return Optional.of(decryptValue(user, field, context, ciphertext));
        } catch (DecryptionFailedException unreadable) {
            return Optional.empty();
        }
    }

    /** Opens a value sealed with {@link #encrypt(User, EncryptedField, String, byte[])} for the same context. */
    @Transactional(propagation = Propagation.MANDATORY)
    public byte[] decrypt(User user, EncryptedField field, String context, byte[] ciphertext) {
        return decryptValue(user, field, context, ciphertext);
    }

    private byte[] decryptValue(User user, EncryptedField field, String context, byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length < 2 || ciphertext[0] != VERSION) {
            throw new DecryptionFailedException("Field ciphertext has an unknown format");
        }
        if (user.getEncryptionKeyId() == null) {
            throw new DecryptionFailedException("The account has no data key");
        }
        byte[] dataKey = unwrap(user);
        try {
            return AesGcm.open(dataKey, Arrays.copyOfRange(ciphertext, 1, ciphertext.length),
                    additionalData(field, user.getId(), context));
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /**
     * Crypto-shreds every data key of the user: each ciphertext sealed under
     * them becomes permanently unreadable, without touching the rows that
     * hold it. The one way to erase an account's encrypted data; see
     * user_encryption_keys in DESIGN.md.
     *
     * @return how many keys were shredded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int shred(UUID userId) {
        return keys.shredAllOf(userId, clock.instant());
    }

    private byte[] createDataKey(User user) {
        Instant now = clock.instant();
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        random.nextBytes(dataKey);
        UUID keyId = UUID.randomUUID();
        keys.save(UserEncryptionKey.create(keyId, user.getId(), kek.wrap(dataKey, keyId, user.getId()), now));
        user.useEncryptionKey(keyId, now);
        return dataKey;
    }

    private byte[] unwrap(User user) {
        UserEncryptionKey key = keys.findById(user.getEncryptionKeyId())
                .filter(UserEncryptionKey::isActive)
                .orElseThrow(() -> new DecryptionFailedException("The account's data key has been shredded"));
        return kek.unwrap(key.getWrappedDek(), key.getId(), user.getId());
    }

    /** Without a context the additional data is exactly what it was before contexts existed. */
    private static String additionalData(EncryptedField field, UUID userId, String context) {
        String base = field.column() + "|" + userId;
        return context == null ? base : base + "|" + context;
    }
}
