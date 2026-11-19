package com.tripmate.identity.service;

import com.tripmate.identity.entity.Role;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second check on every admin action. The filter chain has already
 * required {@code ROLE_ADMIN} and {@code otp} in the token's amr; this re-reads
 * the caller's row, because a token's role can be up to 15 minutes stale: an
 * admin demoted or suspended a minute ago must not keep moderating until the
 * token runs out.
 */
@Component
public class AdminGuard {

    private final UserRepository users;

    public AdminGuard(UserRepository users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public User requireAdmin(SessionPrincipal caller) {
        return users.findById(caller.userId())
                .filter(User::isActive)
                .filter(user -> user.getRole() == Role.ADMIN && user.isTotpEnabled())
                .orElseThrow(() -> new AccessDeniedException("Caller is no longer an admin with 2FA"));
    }
}
