package com.tripmate.identity.service;

import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Account lookups for moderation. Every method starts with {@link AdminGuard}. */
@Service
public class AdminUserService {

    private final AdminGuard adminGuard;

    private final UserRepository users;

    public AdminUserService(AdminGuard adminGuard, UserRepository users) {
        this.adminGuard = adminGuard;
        this.users = users;
    }

    /** Soft-deleted accounts are included: moderation needs to see what happened to them. */
    @Transactional(readOnly = true)
    public User find(SessionPrincipal caller, UUID userId) {
        adminGuard.requireAdmin(caller);
        return users.findById(userId).orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
    }
}
