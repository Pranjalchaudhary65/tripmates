package com.tripmate.meetup.service;

import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.meetup.entity.MeetupCheckin;
import com.tripmate.meetup.repository.MeetupCheckinRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Deletes check-in selfies once their retention is over, the same way
 * verification selfies are purged: the object first, then the key, in one
 * transaction per batch. A deleted object whose row then fails to commit is
 * simply deleted again next time, since deleting a missing object is not an
 * error.
 */
@Component
public class MeetupSelfiePurgeJob {

    private final MeetupCheckinRepository checkins;

    private final ObjectStorage storage;

    private final MeetupProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public MeetupSelfiePurgeJob(MeetupCheckinRepository checkins, ObjectStorage storage, MeetupProperties properties,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.checkins = checkins;
        this.storage = storage;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.meetup.job-interval}",
            initialDelayString = "${tripmate.meetup.job-interval}")
    public void run() {
        purge();
    }

    /** @return how many selfies were deleted */
    public int purge() {
        Instant now = clock.instant();
        Integer purged = transactions.execute(tx -> {
            int count = 0;
            for (MeetupCheckin checkin : checkins.lockSelfiesDueBy(now, properties.jobBatch())) {
                storage.delete(checkin.purgeSelfie(now));
                checkins.save(checkin);
                count++;
            }
            return count;
        });
        return purged == null ? 0 : purged;
    }
}
