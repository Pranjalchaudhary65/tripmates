package com.tripmate.platform.privacy;

import java.util.UUID;

/**
 * A module's part in deleting an account: remove or blank what it holds in
 * the clear about the user. Anything sealed under the user's data key needs
 * nothing here: the key is shredded at the end and it becomes unreadable.
 * Runs inside the deletion job's transaction, before the account row itself
 * is scrubbed.
 */
public interface PersonalDataEraser {

    void erase(UUID userId);
}
