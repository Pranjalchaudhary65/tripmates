package com.tripmate.trust.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes review windows: a review still hidden when its trip's window ends
 * becomes visible, so a person who never reviews back cannot hold someone
 * else's review hostage. Idempotent and safe on several instances: the
 * update only touches rows still hidden.
 */
@Component
public class ReviewWindowJob {

    private final ReviewService reviews;

    public ReviewWindowJob(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Scheduled(fixedDelayString = "${tripmate.trust.job-interval}", initialDelayString = "${tripmate.trust.job-interval}")
    public void run() {
        reviews.revealClosedWindows();
    }
}
