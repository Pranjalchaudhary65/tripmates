package com.tripmate.settlement.service;

import com.tripmate.platform.audit.AuditEvent;
import com.tripmate.platform.audit.AuditLog;
import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.UpiIdService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.outbox.OutboxPublisher;
import com.tripmate.platform.ratelimit.RateLimit;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.settlement.entity.EntryType;
import com.tripmate.settlement.entity.LedgerEntry;
import com.tripmate.settlement.entity.LedgerKind;
import com.tripmate.settlement.entity.LedgerTransaction;
import com.tripmate.settlement.entity.Settlement;
import com.tripmate.settlement.entity.SettlementStatus;
import com.tripmate.settlement.entity.SettlementTransition;
import com.tripmate.settlement.entity.SettlementTransition.Reason;
import com.tripmate.settlement.repository.LedgerEntryRepository;
import com.tripmate.settlement.repository.LedgerTransactionRepository;
import com.tripmate.settlement.repository.SettlementQueries;
import com.tripmate.settlement.repository.SettlementRepository;
import com.tripmate.settlement.repository.SettlementTransitionRepository;
import com.tripmate.settlement.service.DebtSimplifier.Transfer;
import com.tripmate.settlement.service.SettlementViews.ExpenseView;
import com.tripmate.settlement.service.SettlementViews.HistoryItem;
import com.tripmate.settlement.service.SettlementViews.LedgerView;
import com.tripmate.settlement.service.SettlementViews.Member;
import com.tripmate.settlement.service.SettlementViews.NewExpense;
import com.tripmate.settlement.service.SettlementViews.ParticipantShare;
import com.tripmate.settlement.service.SettlementViews.Part;
import com.tripmate.settlement.service.SettlementViews.Payment;
import com.tripmate.settlement.service.SettlementViews.PersonRef;
import com.tripmate.settlement.service.SettlementViews.Role;
import com.tripmate.settlement.service.SettlementViews.SettlementView;
import com.tripmate.settlement.service.SettlementViews.Share;
import com.tripmate.settlement.service.SettlementViews.SplitMethod;
import com.tripmate.settlement.service.SettlementViews.TripRef;
import com.tripmate.trips.service.TripCrew;
import com.tripmate.trips.service.TripCrew.Companion;
import com.tripmate.trips.service.TripCrew.Crew;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A locked trip's shared ledger: who paid for what, who owes whom, and the
 * fewest payments that settle it (DESIGN.md §6). The platform never holds or
 * moves money: an expense is an obligation the payer records, a settlement
 * is a suggestion to pay someone directly over UPI, and only the person paid
 * saying "I received it" writes the payment into the ledger.
 *
 * <p>Who may act: the host and every companion locked in through a public
 * meet-up. A companion who stepped back later can still see the ledger and
 * settle what is theirs, but adds nothing new. Anyone else gets the 404 an
 * unknown ID gets.
 *
 * <p>Every write to one trip first locks that trip's ledger row, so writes
 * run one at a time and the suggested payments are worked out again from
 * the state the last write left. The ledger itself is append-only:
 * corrections are reversing transactions, and the database refuses an
 * unbalanced transaction, an UPDATE or a DELETE.
 */
@Service
@EnableConfigurationProperties(SettlementProperties.class)
public class LedgerService {

    static final String WRITE_BUCKET = "settlement-write";

    static final String READ_BUCKET = "settlement-read";

    public static final String AGGREGATE_SETTLEMENT = "SETTLEMENT";

    public static final String SETTLEMENT_DISPUTED = "SETTLEMENT_DISPUTED";

    static final int MAX_PARTICIPANTS = 20;

    private static final String UNKNOWN_NAME = "Traveller";

    /** Settlements that hold their amount out of the plan: money may already have moved, or a person says it did not. */
    private static final Set<SettlementStatus> HELD = EnumSet.of(SettlementStatus.MARKED_PAID,
            SettlementStatus.DISPUTED);

    private static final Set<SettlementStatus> LIVE = EnumSet.of(SettlementStatus.PROPOSED,
            SettlementStatus.MARKED_PAID, SettlementStatus.DISPUTED);

    private static final Set<SettlementStatus> SHOWN = EnumSet.of(SettlementStatus.PROPOSED,
            SettlementStatus.MARKED_PAID, SettlementStatus.CONFIRMED, SettlementStatus.DISPUTED);

    private final AccountService accounts;

    private final TripCrew crews;

    private final UpiIdService upiIds;

    private final SettlementRepository settlements;

    private final SettlementTransitionRepository transitions;

    private final LedgerTransactionRepository ledger;

    private final LedgerEntryRepository legs;

    private final SettlementQueries queries;

    private final OutboxPublisher outbox;

    private final RateLimiter rateLimiter;

    private final SettlementProperties properties;

    private final TransactionTemplate writing;

    private final TransactionTemplate reading;

    private final AuditLog audit;

    private final Clock clock;

    public LedgerService(AccountService accounts, TripCrew crews, UpiIdService upiIds,
                         SettlementRepository settlements, SettlementTransitionRepository transitions,
                         LedgerTransactionRepository ledger, LedgerEntryRepository legs, SettlementQueries queries,
                         OutboxPublisher outbox, RateLimiter rateLimiter, SettlementProperties properties,
                         PlatformTransactionManager transactionManager, Clock clock, AuditLog audit) {
        this.audit = audit;
        this.accounts = accounts;
        this.crews = crews;
        this.upiIds = upiIds;
        this.settlements = settlements;
        this.transitions = transitions;
        this.ledger = ledger;
        this.legs = legs;
        this.queries = queries;
        this.outbox = outbox;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.writing = new TransactionTemplate(transactionManager);
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.clock = clock;
    }

    /** The settle-up screen in one call: balances, the payments that settle them, recent expenses. */
    public LedgerView view(SessionPrincipal caller, UUID tripId) {
        consume(READ_BUCKET, caller, properties.readLimit());
        return reading.execute(tx -> {
            accounts.requireActive(caller);
            return viewOf(requireCrew(tripId, caller.userId()), caller.userId());
        });
    }

    /** Every transaction on the ledger, newest first. */
    public Page<HistoryItem> history(SessionPrincipal caller, UUID tripId, Pageable pageable) {
        consume(READ_BUCKET, caller, properties.readLimit());
        return reading.execute(tx -> {
            accounts.requireActive(caller);
            requireCrew(tripId, caller.userId());
            Page<LedgerTransaction> page = ledger.findByTrip(tripId, pageable);
            List<UUID> ids = page.getContent().stream().map(LedgerTransaction::getId).toList();
            Map<UUID, List<LedgerEntry>> legsByTransaction = legsOf(ids);
            Set<UUID> people = new HashSet<>();
            legsByTransaction.values().forEach(list -> list.forEach(leg -> people.add(leg.getAccountUserId())));
            Map<UUID, String> names = queries.firstNames(people);
            UUID me = caller.userId();
            return page.map(transaction -> historyItem(transaction,
                    legsByTransaction.getOrDefault(transaction.getId(), List.of()), names, me));
        });
    }

    /**
     * The caller records something they paid for, and who shares it. The
     * expense's client-chosen ID makes a retry return the first result; the
     * same ID with different details is refused.
     */
    public LedgerView addExpense(SessionPrincipal caller, UUID tripId, NewExpense request) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        String description = ExpenseText.clean(request.description())
                .orElseThrow(() -> new ApiProblemException(SettlementProblem.INVALID_DESCRIPTION));
        long amount = request.amountPaise();
        if (amount <= 0 || amount > ExpenseSplit.MAX_EXPENSE_PAISE) {
            throw new ApiProblemException(SettlementProblem.INVALID_AMOUNT);
        }
        Map<UUID, Long> requestedShares = requestedShares(request);
        UUID me = caller.userId();
        String fingerprint = fingerprint(tripId, description, amount, request.split(), requestedShares);
        return write(caller, tripId, (crew, now) -> {
            if (!crew.isLocked()) {
                throw new ApiProblemException(SettlementProblem.LEDGER_NOT_OPEN);
            }
            if (!crew.isTraveller(me)) {
                throw new ApiProblemException(SettlementProblem.NOT_ON_TRIP);
            }
            String key = request.clientExpenseId().toString();
            Optional<LedgerTransaction> earlier = ledger.findByRecordedByUserIdAndIdempotencyKey(me, key);
            if (earlier.isPresent()) {
                if (!earlier.get().getTripId().equals(tripId)
                        || !earlier.get().getRequestFingerprint().equals(fingerprint)) {
                    throw new ApiProblemException(SettlementProblem.EXPENSE_ID_REUSED);
                }
                return;
            }
            if (!crew.travellers().containsAll(requestedShares.keySet())) {
                throw new ApiProblemException(SettlementProblem.PARTICIPANT_NOT_ON_TRIP);
            }
            if (requestedShares.keySet().stream().allMatch(me::equals)) {
                throw new ApiProblemException(SettlementProblem.SPLIT_NEEDS_SOMEONE_ELSE);
            }
            Map<UUID, Long> shares = request.split() == SplitMethod.EQUAL
                    ? ExpenseSplit.equal(amount, requestedShares.keySet())
                    : requestedShares;
            if (!ExpenseSplit.addsUp(amount, shares)) {
                throw new ApiProblemException(SettlementProblem.INVALID_SPLIT);
            }
            LedgerTransaction expense = ledger.save(LedgerTransaction.expense(tripId, me, amount, description, key,
                    fingerprint, now));
            legs.save(LedgerEntry.leg(expense, me, EntryType.DEBIT, amount));
            shares.forEach((user, share) -> legs.save(LedgerEntry.leg(expense, user, EntryType.CREDIT, share)));
            replan(tripId, now);
        });
    }

    /**
     * Corrects an expense by recording its exact opposite; the expense itself
     * stays in the history. Only whoever recorded it can, and only once:
     * asking again changes nothing.
     */
    public LedgerView reverseExpense(SessionPrincipal caller, UUID expenseId) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        UUID tripId = reading.execute(tx -> ledger.findById(expenseId).map(LedgerTransaction::getTripId))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
        UUID me = caller.userId();
        return write(caller, tripId, (crew, now) -> {
            LedgerTransaction expense = ledger.findById(expenseId).orElseThrow();
            if (expense.getKind() != LedgerKind.EXPENSE) {
                throw new ApiProblemException(SettlementProblem.NOT_AN_EXPENSE);
            }
            if (!expense.getRecordedByUserId().equals(me)) {
                throw new ApiProblemException(SettlementProblem.NOT_YOUR_EXPENSE);
            }
            if (ledger.existsByReversesId(expenseId)) {
                return;
            }
            LedgerTransaction reversal = ledger.save(LedgerTransaction.reversalOf(expense, me, now));
            for (LedgerEntry leg : legs.findByTransactions(List.of(expenseId))) {
                legs.save(LedgerEntry.leg(reversal, leg.getAccountUserId(), leg.getEntryType().opposite(),
                        leg.getAmountPaise()));
            }
            replan(tripId, now);
        });
    }

    /** The payer says they sent the money. Saying it again changes nothing. */
    public LedgerView markPaid(SessionPrincipal caller, UUID settlementId) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        UUID me = caller.userId();
        return writeSettlement(caller, settlementId, (settlement, now) -> {
            if (!settlement.getFromUserId().equals(me)) {
                throw new ApiProblemException(SettlementProblem.NOT_THE_PAYER);
            }
            switch (settlement.getStatus()) {
                case MARKED_PAID, CONFIRMED -> {
                    return;
                }
                case DISPUTED, VOIDED -> throw new ApiProblemException(SettlementProblem.SETTLEMENT_CLOSED);
                case PROPOSED -> {
                    settlement.markPaid(now);
                    change(settlement, SettlementStatus.PROPOSED, Reason.MARKED_PAID, me, now);
                }
            }
        });
    }

    /**
     * The payee says they received the money: the one step that writes the
     * ledger, a debit to the payer and a credit to the payee in one
     * balanced transaction. The payer can never take it, whatever state the
     * settlement is in, so one side alone can never move the ledger.
     */
    public LedgerView confirm(SessionPrincipal caller, UUID settlementId) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        UUID me = caller.userId();
        return writeSettlement(caller, settlementId, (settlement, now) -> {
            if (settlement.getFromUserId().equals(me)) {
                throw new ApiProblemException(SettlementProblem.OWN_PAYMENT);
            }
            if (!settlement.getToUserId().equals(me)) {
                throw new ApiProblemException(SettlementProblem.NOT_THE_PAYEE);
            }
            switch (settlement.getStatus()) {
                case CONFIRMED -> {
                    return;
                }
                case PROPOSED -> throw new ApiProblemException(SettlementProblem.NOT_MARKED_PAID);
                case DISPUTED, VOIDED -> throw new ApiProblemException(SettlementProblem.SETTLEMENT_CLOSED);
                case MARKED_PAID -> {
                    settlement.confirm(now);
                    change(settlement, SettlementStatus.MARKED_PAID, Reason.CONFIRMED, me, now);
                    audit.record(me, AuditEvent.SETTLEMENT_CONFIRMED, "SETTLEMENT",
                            settlement.getId(), Map.of("amountPaise", settlement.getAmountPaise()));
                    LedgerTransaction paid = ledger.save(LedgerTransaction.settlement(settlement, now));
                    legs.save(LedgerEntry.leg(paid, settlement.getFromUserId(), EntryType.DEBIT,
                            settlement.getAmountPaise()));
                    legs.save(LedgerEntry.leg(paid, settlement.getToUserId(), EntryType.CREDIT,
                            settlement.getAmountPaise()));
                }
            }
        });
    }

    /**
     * Either person says the payment is wrong: it stops, its amount is held
     * out of the plan, and a moderator is asked to look. A disputed payment
     * is never edited; resolving it is a new settlement.
     */
    public LedgerView dispute(SessionPrincipal caller, UUID settlementId) {
        consume(WRITE_BUCKET, caller, properties.writeLimit());
        UUID me = caller.userId();
        return writeSettlement(caller, settlementId, (settlement, now) -> {
            if (!settlement.getFromUserId().equals(me) && !settlement.getToUserId().equals(me)) {
                throw new ApiProblemException(SettlementProblem.NOT_A_PARTY);
            }
            if (settlement.getStatus() == SettlementStatus.DISPUTED) {
                return;
            }
            if (!settlement.getStatus().isOpen()) {
                throw new ApiProblemException(SettlementProblem.SETTLEMENT_CLOSED);
            }
            SettlementStatus before = settlement.getStatus();
            settlement.dispute(now);
            change(settlement, before, Reason.DISPUTED, me, now);
            outbox.publish(AGGREGATE_SETTLEMENT, settlement.getId(), SETTLEMENT_DISPUTED, Map.of(
                    "settlementId", settlement.getId(), "tripId", settlement.getTripId(), "disputedBy", me));
        });
    }

    /**
     * Works the suggested payments out again from the ledger. Payments marked
     * as sent or disputed hold their amount out, so nobody is asked to pay
     * twice. A suggestion that still matches the new plan exactly is kept, so
     * its ID (and the payer's screen) stays the same; the rest are voided and
     * replaced, all with their history recorded.
     */
    private void replan(UUID tripId, Instant now) {
        Map<UUID, Long> net = balances(tripId);
        List<Settlement> live = settlements.findByTripAndStatuses(tripId, LIVE);
        for (Settlement held : live) {
            if (HELD.contains(held.getStatus())) {
                net.merge(held.getFromUserId(), held.getAmountPaise(), Math::addExact);
                net.merge(held.getToUserId(), -held.getAmountPaise(), Math::addExact);
            }
        }
        List<Transfer> plan = new ArrayList<>(DebtSimplifier.simplify(net));
        for (Settlement proposed : live) {
            if (proposed.getStatus() != SettlementStatus.PROPOSED) {
                continue;
            }
            if (!plan.remove(new Transfer(proposed.getFromUserId(), proposed.getToUserId(),
                    proposed.getAmountPaise()))) {
                proposed.voidForNewPlan(now);
                settlements.save(proposed);
                transitions.save(SettlementTransition.record(proposed.getId(), SettlementStatus.PROPOSED,
                        SettlementStatus.VOIDED, Reason.PLAN_REPLACED, null, now));
            }
        }
        for (Transfer transfer : plan) {
            Settlement created = settlements.save(Settlement.propose(tripId, transfer.from(), transfer.to(),
                    transfer.amountPaise(), now));
            transitions.save(SettlementTransition.record(created.getId(), null, SettlementStatus.PROPOSED,
                    Reason.PLANNED, null, now));
        }
    }

    /** Net paise per account on the trip: positive is owed to them. */
    private Map<UUID, Long> balances(UUID tripId) {
        Map<UUID, Long> net = new HashMap<>();
        for (Object[] row : legs.totalsByAccount(tripId)) {
            net.merge((UUID) row[0], ((EntryType) row[1]).signed(((Number) row[2]).longValue()), Math::addExact);
        }
        return net;
    }

    private void change(Settlement settlement, SettlementStatus from, Reason reason, UUID actor, Instant now) {
        settlements.save(settlement);
        transitions.save(SettlementTransition.record(settlement.getId(), from, settlement.getStatus(), reason, actor,
                now));
    }

    private LedgerView write(SessionPrincipal caller, UUID tripId, Change change) {
        writing.executeWithoutResult(tx -> {
            accounts.requireActive(caller);
            Crew crew = requireCrew(tripId, caller.userId());
            queries.lockTripLedger(tripId);
            change.apply(crew, clock.instant());
        });
        return reading.execute(tx -> viewOf(requireCrew(tripId, caller.userId()), caller.userId()));
    }

    /**
     * A change to one settlement: authorised through its trip like any other
     * ledger write, then read again under the trip's lock so the change
     * starts from the state the last write left.
     */
    private LedgerView writeSettlement(SessionPrincipal caller, UUID settlementId, SettlementChange change) {
        UUID tripId = reading.execute(tx -> settlements.findTripIdById(settlementId))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
        return write(caller, tripId, (crew, now) -> {
            Settlement settlement = settlements.findById(settlementId).orElseThrow();
            change.apply(settlement, now);
            replan(tripId, now);
        });
    }

    /** The trip's crew, if the caller travels (or travelled) on it; the 404 an unknown ID gets otherwise. */
    private Crew requireCrew(UUID tripId, UUID userId) {
        return crews.of(tripId)
                .filter(crew -> crew.everTravelled(userId))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
    }

    private LedgerView viewOf(Crew crew, UUID me) {
        UUID tripId = crew.tripId();
        Instant now = clock.instant();
        Map<UUID, Long> net = balances(tripId);
        List<Settlement> shown = settlements.findByTripAndStatuses(tripId, SHOWN);
        List<LedgerTransaction> expenses = ledger.findByTripAndKind(tripId, LedgerKind.EXPENSE,
                PageRequest.of(0, properties.recentExpenses()));
        List<UUID> expenseIds = expenses.stream().map(LedgerTransaction::getId).toList();
        Set<UUID> reversed = expenseIds.isEmpty() ? Set.of() : new HashSet<>(ledger.findReversed(expenseIds));
        Map<UUID, List<LedgerEntry>> expenseLegs = legsOf(expenseIds);

        Set<UUID> people = new LinkedHashSet<>();
        people.add(crew.hostUserId());
        crew.companions().forEach(companion -> people.add(companion.userId()));
        people.addAll(net.keySet());
        shown.forEach(settlement -> {
            people.add(settlement.getFromUserId());
            people.add(settlement.getToUserId());
        });
        expenseLegs.values().forEach(list -> list.forEach(leg -> people.add(leg.getAccountUserId())));
        Map<UUID, String> names = queries.firstNames(people);

        List<Member> members = new ArrayList<>();
        members.add(new Member(crew.hostUserId(), name(names, crew.hostUserId()), Role.HOST, true,
                crew.hostUserId().equals(me), net.getOrDefault(crew.hostUserId(), 0L)));
        for (Companion companion : crew.companions()) {
            members.add(new Member(companion.userId(), name(names, companion.userId()), Role.COMPANION,
                    companion.onTrip(), companion.userId().equals(me), net.getOrDefault(companion.userId(), 0L)));
        }

        List<SettlementView> settlementViews = shown.stream()
                .map(settlement -> settlementView(settlement, me, names, now))
                .toList();
        List<ExpenseView> expenseViews = expenses.stream()
                .map(expense -> expenseView(expense, expenseLegs.getOrDefault(expense.getId(), List.of()),
                        reversed.contains(expense.getId()), names, me))
                .toList();
        long spent = ledger.sumByTripAndKind(tripId, LedgerKind.EXPENSE)
                - ledger.sumByTripAndKind(tripId, LedgerKind.EXPENSE_REVERSAL);
        boolean settledUp = net.values().stream().allMatch(balance -> balance == 0)
                && shown.stream().noneMatch(settlement -> LIVE.contains(settlement.getStatus()));
        boolean locked = crew.isLocked();
        return new LedgerView(new TripRef(tripId, crew.title(), crew.destinationName(), crew.startDate(),
                crew.endDate()), locked, locked && crew.isTraveller(me), Settlement.CURRENCY, members,
                net.getOrDefault(me, 0L), spent, settlementViews, expenseViews, settledUp);
    }

    private SettlementView settlementView(Settlement settlement, UUID me, Map<UUID, String> names, Instant now) {
        UUID from = settlement.getFromUserId();
        UUID to = settlement.getToUserId();
        SettlementStatus status = settlement.getStatus();
        Part part = from.equals(me) ? Part.PAYER : to.equals(me) ? Part.PAYEE : Part.NONE;
        Payment payment = null;
        if (part == Part.PAYER && status.isOpen()) {
            payment = upiIds.forPayee(to)
                    .map(upi -> new Payment(UpiLinks.payLink(upi.upiId(), name(names, to),
                            settlement.getAmountPaise()), upi.upiId(),
                            upi.updatedAt().isAfter(now.minus(properties.upiChangeWarning()))))
                    .orElse(null);
        }
        return new SettlementView(settlement.getId(), person(names, from), person(names, to),
                settlement.getAmountPaise(), status, settlement.getCreatedAt(), settlement.getMarkedPaidAt(),
                settlement.getConfirmedAt(), part,
                part == Part.PAYER && status == SettlementStatus.PROPOSED,
                part == Part.PAYEE && status == SettlementStatus.MARKED_PAID,
                part != Part.NONE && status.isOpen(), payment);
    }

    private ExpenseView expenseView(LedgerTransaction expense, List<LedgerEntry> expenseLegs, boolean reversed,
                                    Map<UUID, String> names, UUID me) {
        List<Share> shares = expenseLegs.stream()
                .filter(leg -> leg.getEntryType() == EntryType.CREDIT)
                .sorted(Comparator.comparing(leg -> name(names, leg.getAccountUserId())))
                .map(leg -> new Share(person(names, leg.getAccountUserId()), leg.getAmountPaise()))
                .toList();
        return new ExpenseView(expense.getId(), expense.getDescription(), expense.getAmountPaise(),
                person(names, expense.getPaidByUserId()), expense.getCreatedAt(), shares, reversed,
                !reversed && expense.getRecordedByUserId().equals(me));
    }

    private static HistoryItem historyItem(LedgerTransaction transaction, List<LedgerEntry> transactionLegs,
                                           Map<UUID, String> names, UUID me) {
        long myEffect = transactionLegs.stream()
                .filter(leg -> leg.getAccountUserId().equals(me))
                .mapToLong(leg -> leg.getEntryType().signed(leg.getAmountPaise()))
                .sum();
        PersonRef paidBy = transaction.getKind() == LedgerKind.EXPENSE
                ? person(names, transaction.getPaidByUserId()) : null;
        PersonRef from = null;
        PersonRef to = null;
        if (transaction.getKind() == LedgerKind.SETTLEMENT) {
            for (LedgerEntry leg : transactionLegs) {
                if (leg.getEntryType() == EntryType.DEBIT) {
                    from = person(names, leg.getAccountUserId());
                } else {
                    to = person(names, leg.getAccountUserId());
                }
            }
        }
        return new HistoryItem(transaction.getId(), transaction.getKind(), transaction.getCreatedAt(),
                transaction.getDescription(), transaction.getAmountPaise(), paidBy, from, to,
                transaction.getReversesId(), myEffect);
    }

    private Map<UUID, List<LedgerEntry>> legsOf(List<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        return legs.findByTransactions(transactionIds).stream()
                .collect(Collectors.groupingBy(LedgerEntry::getTransactionId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    /**
     * The participants as asked for: unique, at most twenty, with a share
     * each for an exact split and none for an equal one.
     */
    private static Map<UUID, Long> requestedShares(NewExpense request) {
        List<ParticipantShare> participants = request.participants();
        if (participants == null || participants.isEmpty() || participants.size() > MAX_PARTICIPANTS) {
            throw new ApiProblemException(SettlementProblem.INVALID_SPLIT);
        }
        Map<UUID, Long> shares = new LinkedHashMap<>();
        for (ParticipantShare participant : participants) {
            boolean exact = request.split() == SplitMethod.EXACT;
            if (participant.userId() == null || (exact != (participant.sharePaise() != null))
                    || shares.containsKey(participant.userId())) {
                throw new ApiProblemException(SettlementProblem.INVALID_SPLIT);
            }
            shares.put(participant.userId(), exact ? participant.sharePaise() : 0L);
        }
        return shares;
    }

    /** SHA-256 over everything the expense says, in a fixed order, so a retry can be told from a reuse. */
    static String fingerprint(UUID tripId, String description, long amount, SplitMethod split,
                              Map<UUID, Long> shares) {
        StringBuilder canonical = new StringBuilder()
                .append(tripId).append('\n')
                .append(description).append('\n')
                .append(amount).append('\n')
                .append(split).append('\n');
        shares.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PersonRef person(Map<UUID, String> names, UUID userId) {
        return new PersonRef(userId, name(names, userId));
    }

    private static String name(Map<UUID, String> names, UUID userId) {
        return names.getOrDefault(userId, UNKNOWN_NAME);
    }

    private void consume(String bucket, SessionPrincipal caller, RateLimit limit) {
        rateLimiter.consume(bucket, caller.userId().toString(), limit);
    }

    @FunctionalInterface
    private interface Change {
        void apply(Crew crew, Instant now);
    }

    @FunctionalInterface
    private interface SettlementChange {
        void apply(Settlement settlement, Instant now);
    }
}
