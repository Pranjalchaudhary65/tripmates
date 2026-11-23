package com.tripmate.vibe.service;

import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.outbox.OutboxPublisher;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.vibe.entity.DealBreakers;
import com.tripmate.vibe.entity.GenderPreference;
import com.tripmate.vibe.entity.HabitPreference;
import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.VibeProfile;
import com.tripmate.vibe.entity.VibeQuizDraft;
import com.tripmate.vibe.repository.DealBreakersRepository;
import com.tripmate.vibe.repository.VibeProfileRepository;
import com.tripmate.vibe.repository.VibeQuizDraftRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The caller's own vibe quiz, profile and deal-breakers. There is no other
 * user's vibe to ask for: every method acts on the account the access token
 * names. Writes lock that account's user row first (AccountService), so a
 * quiz submitted from two tabs at once lands once, not as a key conflict.
 *
 * <p>A change to the profile or the deal-breakers is recorded in the outbox
 * in the same transaction: matching keeps a score cache that must be dropped
 * when either side's vibe changes, and it learns of that from there.
 */
@Service
@EnableConfigurationProperties(VibeProperties.class)
public class VibeService {

    public static final String VIBE_PROFILE_CHANGED = "VIBE_PROFILE_CHANGED";

    public static final String DEAL_BREAKERS_CHANGED = "DEAL_BREAKERS_CHANGED";

    static final String BUCKET = "vibe-account";

    private static final String AGGREGATE_USER = "USER";

    private static final TypeReference<Map<String, String>> ANSWERS = new TypeReference<>() {
    };

    private final AccountService accounts;

    private final VibeProfileRepository profiles;

    private final DealBreakersRepository dealBreakers;

    private final VibeQuizDraftRepository drafts;

    private final OutboxPublisher outbox;

    private final RateLimiter rateLimiter;

    private final VibeProperties properties;

    private final JsonMapper json;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Clock clock;

    public VibeService(AccountService accounts, VibeProfileRepository profiles, DealBreakersRepository dealBreakers,
                       VibeQuizDraftRepository drafts, OutboxPublisher outbox, RateLimiter rateLimiter,
                       VibeProperties properties, JsonMapper json, PlatformTransactionManager transactionManager,
                       Clock clock) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.dealBreakers = dealBreakers;
        this.drafts = drafts;
        this.outbox = outbox;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /** The questions, with the caller's unfinished answers if there are any for this version of the quiz. */
    public QuizView quiz(SessionPrincipal caller) {
        return readOnly.execute(status -> {
            accounts.requireActive(caller);
            UUID userId = caller.userId();
            Draft draft = drafts.findById(userId)
                    .filter(saved -> saved.getQuizVersion() == VibeQuiz.VERSION)
                    .map(saved -> new Draft(readAnswers(saved.getAnswers()), saved.getUpdatedAt()))
                    .orElse(null);
            return new QuizView(VibeQuiz.VERSION, draft, profiles.findById(userId).isPresent());
        });
    }

    /** Keeps the answers given so far. Each one must already be valid; gaps are fine. */
    public Draft saveDraft(SessionPrincipal caller, Map<String, String> answers) {
        consumeWrite(caller);
        Map<String, String> checked = requireValid(answers);
        return transactions.execute(status -> {
            accounts.lockActive(caller);
            Instant now = clock.instant();
            VibeQuizDraft draft = drafts.findById(caller.userId())
                    .orElseGet(() -> VibeQuizDraft.create(caller.userId()));
            draft.save(writeAnswers(checked), VibeQuiz.VERSION, now);
            drafts.save(draft);
            return new Draft(checked, now);
        });
    }

    /** Scores a complete set of answers into the profile, replacing any earlier one, and drops the draft. */
    public ProfileView submit(SessionPrincipal caller, Map<String, String> answers) {
        consumeWrite(caller);
        Map<String, String> checked = requireValid(answers);
        if (!checked.keySet().containsAll(VibeQuiz.questions().stream().map(VibeQuiz.Question::id).toList())) {
            throw new ApiProblemException(VibeProblem.QUIZ_INCOMPLETE);
        }
        VibeDimensions dimensions = VibeQuiz.score(checked);
        return transactions.execute(status -> {
            accounts.lockActive(caller);
            UUID userId = caller.userId();
            Instant now = clock.instant();
            String raw = writeAnswers(checked);
            VibeProfile profile = profiles.findById(userId)
                    .map(existing -> {
                        existing.retakeQuiz(dimensions, raw, VibeQuiz.VERSION, now);
                        return existing;
                    })
                    .orElseGet(() -> VibeProfile.fromQuiz(userId, dimensions, raw, VibeQuiz.VERSION, now));
            profiles.save(profile);
            drafts.deleteById(userId);
            outbox.publish(AGGREGATE_USER, userId, VIBE_PROFILE_CHANGED, Map.of("userId", userId));
            return new ProfileView(profile, dealBreakers.findById(userId).orElse(null));
        });
    }

    public ProfileView profile(SessionPrincipal caller) {
        return readOnly.execute(status -> {
            accounts.requireActive(caller);
            UUID userId = caller.userId();
            return new ProfileView(profiles.findById(userId).orElse(null), dealBreakers.findById(userId).orElse(null));
        });
    }

    /** The user's own correction of what the quiz worked out. Needs a profile to correct. */
    public ProfileView adjust(SessionPrincipal caller, VibeDimensions dimensions) {
        consumeWrite(caller);
        return transactions.execute(status -> {
            accounts.lockActive(caller);
            UUID userId = caller.userId();
            VibeProfile profile = profiles.findById(userId)
                    .orElseThrow(() -> new ApiProblemException(VibeProblem.VIBE_PROFILE_MISSING));
            profile.adjust(dimensions, clock.instant());
            profiles.save(profile);
            outbox.publish(AGGREGATE_USER, userId, VIBE_PROFILE_CHANGED, Map.of("userId", userId));
            return new ProfileView(profile, dealBreakers.findById(userId).orElse(null));
        });
    }

    /** Sets every deal-breaker at once; they do not need a profile, since they filter before any score. */
    public ProfileView replaceDealBreakers(SessionPrincipal caller, HabitPreference smoking, HabitPreference drinking,
                                           GenderPreference genderPreference, int minTrustScore) {
        consumeWrite(caller);
        return transactions.execute(status -> {
            accounts.lockActive(caller);
            UUID userId = caller.userId();
            Instant now = clock.instant();
            DealBreakers row = dealBreakers.findById(userId).orElseGet(() -> DealBreakers.create(userId, now));
            row.replace(smoking, drinking, genderPreference, minTrustScore, now);
            dealBreakers.save(row);
            outbox.publish(AGGREGATE_USER, userId, DEAL_BREAKERS_CHANGED, Map.of("userId", userId));
            return new ProfileView(profiles.findById(userId).orElse(null), row);
        });
    }

    private void consumeWrite(SessionPrincipal caller) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), properties.writeLimit());
    }

    /** Every entry must be a question of this quiz answered with one of its options; sorted for a stable JSON. */
    private static Map<String, String> requireValid(Map<String, String> answers) {
        Map<String, String> checked = new TreeMap<>();
        if (answers == null) {
            return checked;
        }
        answers.forEach((questionId, optionId) -> {
            if (questionId == null || optionId == null || !VibeQuiz.isAnswer(questionId, optionId)) {
                throw new ApiProblemException(VibeProblem.INVALID_QUIZ_ANSWER);
            }
            checked.put(questionId, optionId);
        });
        return checked;
    }

    private String writeAnswers(Map<String, String> answers) {
        return json.writeValueAsString(answers);
    }

    private Map<String, String> readAnswers(String stored) {
        return json.readValue(stored, ANSWERS);
    }

    /** @param answers question ID to option ID */
    public record Draft(Map<String, String> answers, Instant updatedAt) {
    }

    /**
     * @param draft      null when there is nothing unfinished for this version
     * @param hasProfile the quiz has been finished at least once
     */
    public record QuizView(int version, Draft draft, boolean hasProfile) {
    }

    /** Either part may be null: not taken the quiz yet, or no deal-breakers set. */
    public record ProfileView(VibeProfile profile, DealBreakers dealBreakers) {
    }
}
