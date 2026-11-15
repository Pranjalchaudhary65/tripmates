package com.tripmate.identity.repository;

import com.tripmate.identity.entity.LoginAttempt;
import org.springframework.data.repository.Repository;

import java.util.UUID;

/**
 * Insert and look up only. Extends the bare {@link Repository} rather than
 * JpaRepository so there is no delete or bulk method to call by mistake.
 */
public interface LoginHistoryRepository extends Repository<LoginAttempt, UUID> {

    LoginAttempt save(LoginAttempt attempt);

    /** Served by idx_login_history_user_device. */
    boolean existsByUserIdAndDeviceIdHashAndSuccessTrue(UUID userId, String deviceIdHash);

    boolean existsByUserIdAndSuccessTrue(UUID userId);
}
