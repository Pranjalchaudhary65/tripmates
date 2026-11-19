package com.tripmate.identity.repository;

import com.tripmate.identity.entity.UserEncryptionKey;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert, read, and shred. No delete: the row has to outlive its key, so
 * ciphertext elsewhere still points at a key ID, just one that can no longer
 * be unwrapped.
 */
public interface UserEncryptionKeyRepository extends Repository<UserEncryptionKey, UUID> {

    UserEncryptionKey save(UserEncryptionKey key);

    Optional<UserEncryptionKey> findById(UUID id);

    /**
     * Crypto-shredding: overwrites the wrapped key with zero bytes, so even
     * the KEK can no longer recover it, and marks it revoked.
     *
     * @return how many live keys were shredded
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update user_encryption_keys
               set active = false, revoked_at = :now, wrapped_dek = decode('', 'hex')
             where user_id = :userId and active""", nativeQuery = true)
    int shredAllOf(@Param("userId") UUID userId, @Param("now") Instant now);
}
