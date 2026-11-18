package com.tripmate.identity.service;

import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads of the caller's own account. The user ID always comes from the verified token, never from the request. */
@Service
public class AccountService {

    private final UserRepository users;

    public AccountService(UserRepository users) {
        this.users = users;
    }

    /**
     * A token can outlive a change to its account by up to its lifetime; an
     * account that is no longer active is answered as an ended session.
     */
    @Transactional(readOnly = true)
    public User currentUser(SessionPrincipal caller) {
        return users.findById(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
    }
}
