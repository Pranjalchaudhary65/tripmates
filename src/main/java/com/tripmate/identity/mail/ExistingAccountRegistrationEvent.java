package com.tripmate.identity.mail;

/**
 * Someone tried to register an address that already belongs to a verified
 * account. The API answers exactly as it does for a new address; the account
 * owner learns about it through their inbox instead.
 */
public record ExistingAccountRegistrationEvent(String email) {

    @Override
    public String toString() {
        return "ExistingAccountRegistrationEvent[]";
    }
}
