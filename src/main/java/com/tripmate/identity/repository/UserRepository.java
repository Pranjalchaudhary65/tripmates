package com.tripmate.identity.repository;

import com.tripmate.identity.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Case-insensitive because the column is citext. The JDBC URL sends
     * strings untyped (stringtype=unspecified), so PostgreSQL resolves this
     * parameter as citext; sent as varchar, the comparison would silently fall
     * back to case-sensitive text equality.
     *
     * <p>Row-locked so two registrations for the same pending address are
     * applied one after the other, never interleaved.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email and u.deletedAt is null")
    Optional<User> findLiveByEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
