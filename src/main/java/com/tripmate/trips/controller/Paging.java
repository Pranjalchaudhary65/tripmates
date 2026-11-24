package com.tripmate.trips.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Page parameters from a query string, clamped rather than refused, as the
 * admin queue does: a client asking for page -1 or 10 000 rows gets the
 * nearest page that is allowed. The page number is capped too, so no offset
 * can run into the millions.
 */
final class Paging {

    static final int DEFAULT_SIZE = 20;

    static final int MAX_SIZE = 50;

    static final int MAX_PAGE = 1_000;

    private Paging() {
    }

    static Pageable of(int page, int size) {
        return PageRequest.of(Math.clamp(page, 0, MAX_PAGE), Math.clamp(size, 1, MAX_SIZE));
    }
}
