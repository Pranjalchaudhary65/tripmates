package com.tripmate.moderation.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.identity.service.AccountModeration;
import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.UserBlocks;
import com.tripmate.identity.service.UserBlocks.BlockView;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.moderation.repository.ReportQueries;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.error.Constraints;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.trips.service.TripContacts;
import com.tripmate.trips.service.TripContacts.Contact;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Blocking and reporting (DESIGN.md §9, as built).
 *
 * <p>Both are aimed at a person through something the caller shares with
 * them: a match (the caller is its host or its companion) or a trip both
 * travelled on. Anything else, a stranger's ID included, is the 404 an
 * unknown match gets, so neither route can probe who exists.
 *
 * <p>A report is a case for a moderator. Its free text is sealed under the
 * reporter's key. When {@code auto-suspend-reporters} different people have
 * reported one account within {@code auto-suspend-window} (dismissed reports
 * not counted), the account is suspended until a moderator looks: its
 * sessions end and its vouchers are penalised. Reports about one account are
 * counted under that account's row lock, so simultaneous reports cannot
 * both see a count one short.
 *
 * <p>The reporter is never told whether anything happened to the person
 * they reported, and the reported person is never told who reported them.
 */
@Service
@EnableConfigurationProperties(ModerationProperties.class)
public class ModerationService {

    static final String WRITE_BUCKET = "moderation-write";

    static final String READ_BUCKET = "moderation-read";

    private final TripContacts contacts;

    private final UserBlocks blocks;

    private final AccountModeration accounts;

    private final AccountService accountService;

    private final ReportQueries reports;

    private final UserDataCipher cipher;

    private final RateLimiter rateLimiter;

    private final ModerationProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public ModerationService(TripContacts contacts, UserBlocks blocks, AccountModeration accounts,
                             AccountService accountService, ReportQueries reports, UserDataCipher cipher,
                             RateLimiter rateLimiter, ModerationProperties properties,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.contacts = contacts;
        this.blocks = blocks;
        this.accounts = accounts;
        this.accountService = accountService;
        this.reports = reports;
        this.cipher = cipher;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public BlockView block(SessionPrincipal caller, Target target) {
        rateLimiter.consume(WRITE_BUCKET, caller.userId().toString(), properties.writeLimit());
        return Objects.requireNonNull(transactions.execute(status -> {
            accountService.lockActive(caller);
            Contact contact = resolve(caller.userId(), target);
            return blocks.block(caller.userId(), contact.userId(), clock.instant());
        }));
    }

    public void unblock(SessionPrincipal caller, UUID blockId) {
        rateLimiter.consume(WRITE_BUCKET, caller.userId().toString(), properties.writeLimit());
        transactions.executeWithoutResult(status -> {
            accountService.lockActive(caller);
            blocks.unblock(caller.userId(), blockId);
        });
    }

    public List<BlockView> blocked(SessionPrincipal caller) {
        rateLimiter.consume(READ_BUCKET, caller.userId().toString(), properties.readLimit());
        return Objects.requireNonNull(transactions.execute(status -> {
            accountService.lockActive(caller);
            return blocks.blockedBy(caller.userId());
        }));
    }

    public ReportView report(SessionPrincipal caller, Target target, ReportReason reason, String rawDetails) {
        rateLimiter.consume(WRITE_BUCKET, caller.userId().toString(), properties.writeLimit());
        if (reason == null) {
            throw new ApiProblemException(ModerationProblem.INVALID_REASON);
        }
        String details;
        try {
            details = ModerationText.clean(rawDetails).orElse(null);
        } catch (IllegalArgumentException tooLong) {
            throw new ApiProblemException(ModerationProblem.INVALID_DETAILS);
        }
        UUID me = caller.userId();
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                accountService.lockActive(caller);
                Contact contact = resolve(me, target);
                accounts.lock(contact.userId());
                if (reports.hasOpenReport(me, contact.userId())) {
                    throw new ApiProblemException(ModerationProblem.ALREADY_REPORTED);
                }
                UUID id = UUID.randomUUID();
                Instant now = clock.instant();
                byte[] sealed = details == null ? null : cipher.seal(me, EncryptedField.REPORT_DETAILS,
                        "report:" + id, details.getBytes(StandardCharsets.UTF_8));
                reports.insert(id, me, contact.userId(), contact.tripId(), reason.name(), sealed, now);
                if (reports.distinctReportersSince(contact.userId(), now.minus(properties.autoSuspendWindow()))
                        >= properties.autoSuspendReporters()) {
                    accounts.suspend(contact.userId());
                }
                return new ReportView(id, reason, now);
            }));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, "uq_reports_open_pair")) {
                throw new ApiProblemException(ModerationProblem.ALREADY_REPORTED);
            }
            throw e;
        }
    }

    private Contact resolve(UUID me, Target target) {
        return (target.matchId() != null
                ? contacts.otherSideOfMatch(me, target.matchId())
                : contacts.fellowTraveller(me, target.tripId(), target.userId()))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
    }

    /** Either a match, or a trip and a person on it; checked to be exactly one by {@link #of}. */
    public record Target(UUID matchId, UUID tripId, UUID userId) {

        public static Target of(UUID matchId, UUID tripId, UUID userId) {
            boolean byMatch = matchId != null && tripId == null && userId == null;
            boolean byTrip = matchId == null && tripId != null && userId != null;
            if (!byMatch && !byTrip) {
                throw new ApiProblemException(ModerationProblem.INVALID_TARGET);
            }
            return new Target(matchId, tripId, userId);
        }
    }

    /** What the reporter sees of their report: that it was received. Never its outcome. */
    public record ReportView(UUID id, ReportReason reason, Instant receivedAt) {
    }
}
