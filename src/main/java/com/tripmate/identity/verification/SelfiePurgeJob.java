package com.tripmate.identity.verification;

import com.tripmate.identity.entity.IdentityVerification;
import com.tripmate.identity.entity.VerificationRejection;
import com.tripmate.identity.repository.IdentityVerificationRepository;
import com.tripmate.identity.upload.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Deletes every selfie image whose retention window has passed, whatever its
 * verification decided. A selfie still waiting for review when its window
 * ends is rejected as REVIEW_EXPIRED: there is nothing left for a reviewer to
 * look at, and nothing may stay pending without its evidence.
 *
 * <p>The file is deleted first and the row updated after, in the same
 * transaction. If the update fails, the row still names the key and the next
 * run deletes again (a no-op) and updates; the opposite order could commit a
 * row that says "purged" over a file that survived. Rows are claimed with
 * SKIP LOCKED, so instances running this at the same moment share the work.
 */
@Component
@EnableConfigurationProperties(SelfieProperties.class)
public class SelfiePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(SelfiePurgeJob.class);

    private final IdentityVerificationRepository verifications;

    private final ObjectStorage storage;

    private final SelfieProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public SelfiePurgeJob(IdentityVerificationRepository verifications, ObjectStorage storage,
                          SelfieProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.verifications = verifications;
        this.storage = storage;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.identity.selfie.purge-interval}",
            initialDelayString = "${tripmate.identity.selfie.purge-initial-delay}")
    public void run() {
        int purged = purgeDue();
        if (purged > 0) {
            log.info("Purged {} selfie images past their retention window", purged);
        }
    }

    /** @return how many images were purged */
    public int purgeDue() {
        int total = 0;
        while (true) {
            Integer batch = transactions.execute(status -> purgeBatch(clock.instant()));
            int count = batch == null ? 0 : batch;
            total += count;
            if (count < properties.purgeBatchSize()) {
                return total;
            }
        }
    }

    private int purgeBatch(Instant now) {
        List<IdentityVerification> due = verifications.lockDueForPurge(now, properties.purgeBatchSize());
        for (IdentityVerification verification : due) {
            storage.delete(verification.getSelfieImageKey());
            if (verification.isPending()) {
                verification.withdraw(VerificationRejection.REVIEW_EXPIRED, now);
            }
            verification.markPurged(now);
        }
        return due.size();
    }
}
