package com.tripmate.chat.service;

import com.tripmate.chat.entity.Conversation;
import com.tripmate.chat.repository.ChatMessageRepository;
import com.tripmate.chat.repository.ChatQueries;
import com.tripmate.chat.repository.ChatQueries.ConversationCard;
import com.tripmate.chat.repository.ConversationRepository;
import com.tripmate.chat.service.ChatEvents.ReadMoved;
import com.tripmate.chat.service.ChatViews.ConversationView;
import com.tripmate.chat.service.ChatViews.InboxItem;
import com.tripmate.chat.service.ChatViews.MessagePage;
import com.tripmate.chat.service.ContactRedactor.Redaction;
import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.ContactDirectory;
import com.tripmate.identity.service.ContactDirectory.Contact;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.trips.service.CompanionAccepted;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversations: opened when a host accepts a companion, then listed, read,
 * marked read, and, once the trip locks, the way to the other person's real
 * contact details.
 *
 * <p>Every read is narrowed to the caller's own conversations in SQL; a
 * conversation ID the caller is not in, and one that does not exist, both
 * answer the same 404.
 */
@Service
public class ConversationService {

    static final String READ_BUCKET = "chat-read";

    /** Messages on the conversation screen when it opens, and the most a history page holds. */
    public static final int PAGE_SIZE = 50;

    static final int MAX_ICEBREAKER_LENGTH = 200;

    static final int MAX_ICEBREAKERS = 3;

    private final AccountService accounts;

    private final ConversationRepository conversations;

    private final ChatMessageRepository messages;

    private final ChatQueries queries;

    private final MessageReader reader;

    private final IcebreakerGenerator icebreakers;

    private final ContactDirectory contacts;

    private final RateLimiter rateLimiter;

    private final ChatProperties properties;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Clock clock;

    public ConversationService(AccountService accounts, ConversationRepository conversations,
                               ChatMessageRepository messages, ChatQueries queries, MessageReader reader,
                               IcebreakerGenerator icebreakers, ContactDirectory contacts, RateLimiter rateLimiter,
                               ChatProperties properties, ApplicationEventPublisher events,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.accounts = accounts;
        this.conversations = conversations;
        this.messages = messages;
        this.queries = queries;
        this.reader = reader;
        this.icebreakers = icebreakers;
        this.contacts = contacts;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * Runs inside the accept's own transaction, so a conversation exists
     * exactly when the acceptance committed. An accept can never be repeated
     * for one request, but the check keeps this safe to replay anyway.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCompanionAccepted(CompanionAccepted accepted) {
        if (!conversations.existsByTripIdAndRequesterUserId(accepted.tripId(), accepted.companionUserId())) {
            conversations.save(Conversation.open(accepted.tripId(), accepted.hostUserId(),
                    accepted.companionUserId(), accepted.acceptedAt()));
        }
    }

    public Page<InboxItem> inbox(SessionPrincipal caller, Pageable pageable) {
        consumeRead(caller);
        return readOnly.execute(tx -> {
            accounts.requireActive(caller);
            return queries.inbox(caller.userId(), pageable).map(card -> reader.inboxItem(card, caller.userId()));
        });
    }

    /** The conversation screen in one call: its header and the newest messages. */
    public ConversationView view(SessionPrincipal caller, UUID conversationId) {
        consumeRead(caller);
        return readOnly.execute(tx -> {
            accounts.requireActive(caller);
            ConversationCard card = requireCard(caller, conversationId);
            return new ConversationView(reader.inboxItem(card, caller.userId()),
                    reader.page(caller.userId(), conversationId, Long.MAX_VALUE, PAGE_SIZE));
        });
    }

    /** Older messages, below {@code beforeSeq}, oldest first. */
    public MessagePage history(SessionPrincipal caller, UUID conversationId, long beforeSeq, int size) {
        consumeRead(caller);
        return readOnly.execute(tx -> {
            accounts.requireActive(caller);
            requireCard(caller, conversationId);
            return reader.page(caller.userId(), conversationId, beforeSeq, size);
        });
    }

    /**
     * Moves the caller's read marker forward. A marker past the last message
     * stops at it; one behind the current marker changes nothing.
     */
    public void markRead(SessionPrincipal caller, UUID conversationId, long upToSeq) {
        consumeRead(caller);
        transactions.executeWithoutResult(tx -> {
            accounts.requireActive(caller);
            Conversation conversation = conversations.findByIdForUpdate(conversationId)
                    .filter(found -> found.includes(caller.userId()))
                    .orElseThrow(ConversationService::notFound);
            if (conversation.markRead(caller.userId(), upToSeq, clock.instant())) {
                conversations.save(conversation);
                events.publishEvent(new ReadMoved(conversationId, caller.userId(), messages.countUnread(
                        conversationId, caller.userId(), conversation.readSeqOf(caller.userId()))));
            }
        });
    }

    /**
     * Up to three opening lines for the caller, from the trip's facts. Each
     * candidate goes through the same cleaning and redaction as a message,
     * and one that would carry a contact detail, or comes back empty or too
     * long, is dropped rather than shown; the next candidate takes its place.
     */
    public List<String> icebreakers(SessionPrincipal caller, UUID conversationId) {
        consumeRead(caller);
        ConversationCard card = readOnly.execute(tx -> {
            accounts.requireActive(caller);
            return requireCard(caller, conversationId);
        });
        List<String> suggestions = icebreakers.suggest(new IcebreakerGenerator.Context(card.id(),
                card.trip().destinationName(), card.trip().startDate(), card.trip().endDate(),
                card.trip().budgetBand(), card.viewerIsHost(), card.other().firstName()));
        List<String> safe = new ArrayList<>();
        for (String suggestion : suggestions == null ? List.<String>of() : suggestions) {
            if (safe.size() == MAX_ICEBREAKERS) {
                break;
            }
            String clean = MessageSanitizer.clean(suggestion);
            Redaction redaction = ContactRedactor.redact(clean);
            if (!clean.isEmpty() && !redaction.redacted()
                    && clean.codePointCount(0, clean.length()) <= MAX_ICEBREAKER_LENGTH && !safe.contains(clean)) {
                safe.add(clean);
            }
        }
        return safe;
    }

    /**
     * The other person's real contact details, only once the trip has locked
     * for this conversation. Before that the answer is 409, never the data.
     */
    public Contact contact(SessionPrincipal caller, UUID conversationId) {
        consumeRead(caller);
        return readOnly.execute(tx -> {
            accounts.requireActive(caller);
            ConversationCard card = requireCard(caller, conversationId);
            if (card.contactUnlockedAt() == null) {
                throw new ApiProblemException(ChatProblem.CONTACT_LOCKED);
            }
            return contacts.contactOf(card.otherUserId())
                    .orElseThrow(() -> new ApiProblemException(ChatProblem.CONVERSATION_CLOSED));
        });
    }

    /**
     * For the socket's typing notice: the other person in the conversation,
     * when the caller is in it and it still takes messages; empty otherwise,
     * and the notice is then dropped without a word.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> typingRecipient(UUID callerId, UUID conversationId) {
        return conversations.findById(conversationId)
                .filter(conversation -> conversation.includes(callerId) && conversation.isActive())
                .map(conversation -> conversation.otherThan(callerId))
                .filter(queries::isActiveUser);
    }

    private ConversationCard requireCard(SessionPrincipal caller, UUID conversationId) {
        return queries.find(caller.userId(), conversationId).orElseThrow(ConversationService::notFound);
    }

    private void consumeRead(SessionPrincipal caller) {
        rateLimiter.consume(READ_BUCKET, caller.userId().toString(), properties.readLimit());
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(CommonProblem.NOT_FOUND);
    }
}
