package com.tripmate.platform.privacy;

import java.util.UUID;

/**
 * A module's share of a user's data export. Each module exports what it
 * owns, about that user only, so the export never needs one module to read
 * another's tables. Called inside the export job's transaction.
 */
public interface PersonalDataContributor {

    /** The export's top-level key for this module, e.g. "trips". */
    String section();

    /** Plain values, maps and lists only; serialised as JSON. Decrypted where the data is the user's own. */
    Object export(UUID userId);
}
