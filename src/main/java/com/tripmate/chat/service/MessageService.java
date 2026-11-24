package com.tripmate.chat.service;

import com.tripmate.chat.entity.AutoAction;
import com.tripmate.chat.entity.ChatMessage;
import com.tripmate.chat.entity.Conversation;
import com.tripmate.chat.entity.FlagType;
import com.tripmate.chat.entity.ModerationFlag;
import com.tripmate.chat.repository.ChatMessageRepository;
import com.tripmate.chat.repository.ChatQueries;
import com.tripmate.chat.repository.ConversationRepository;
import com.tripmate.chat.repository.ModerationFlagRepository;
import com.tripmate.chat.service.ChatEvents.MessagePosted;
import com.tripmate.chat.service.ChatViews.MessageView;
import com.tripmate.chat.service.ChatViews.Sent;
import com.tripmate.chat.service.ContactRedactor.Redaction;
import com.tripmate.chat.service.MessageClassifier.Label;
import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sending a message. The order of the work is the security argument:
 *
 * <ol>
 *   <li>the text is cleaned to plain text ({@link MessageSanitizer}) and
 *       contact details are redacted ({@link ContactRedactor}) before any
 *       transaction opens, so nothing unredacted is ever handed to the
 *       database or a log;</li>
 *   <li>the moderation labels are worked out from the cleaned text before
 *       redaction (a pasted number is itself the signal) and that text is
 *       then dropped;</li>
 *   <li>under the sender's account lock and the conversation's row lock,
 *       the caller is checked to be one of its two people (anyone else gets
 *       the 404 an unknown ID gets), the next message number is taken, and
 *       the redacted text is sealed under the sender's data key, bound to
 *       that conversation and number.</li>
 * </ol>
 *
 * <p>A flagged message is still delivered: the label warns both people and
 * queues the message for review. Only a label at or above
 * {@code penalty-confidence} also records a trust score input.
 *
 * <p>Sending is idempotent per client message ID: a retry after a lost
 * response returns the stored message instead of sending it twice, and the
 * same ID with different text is refused.
 */
@Service
@EnableConfigurationProperties(ChatProperties.class)
public class MessageService {

    static final String SEND_BUCKET = "chat-send";

    private final AccountService accounts;

    private final ConversationRepository conversations;

    private final ChatMessageRepository messages;

    private final ModerationFlagRepository flags;

    private final ChatQueries queries;

    private final MessageReader reader;

    private final UserDataCipher cipher;

    private final MessageClassifier classifier;

    private final ChatTrustEvents trustEvents;

    private final RateLimiter rateLimiter;

    private final ChatProperties properties;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public MessageService(AccountService accounts, ConversationRepository conversations,
                          ChatMessageRepository messages, ModerationFlagRepository flags, ChatQueries queries,
                          MessageReader reader, UserDataCipher cipher, MessageClassifier classifier,
                          ChatTrustEvents trustEvents, RateLimiter rateLimiter, ChatProperties properties,
                          ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                          Clock clock) {
        this.accounts = accounts;
        this.conversations = conversations;
        this.messages = messages;
        this.flags = flags;
        this.queries = queries;
        this.reader = reader;
        this.cipher = cipher;
        this.classifier = classifier;
        this.trustEvents = trustEvents;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Sent send(SessionPrincipal caller, UUID conversationId, UUID clientMessageId, String rawBody) {
        UUID senderId = caller.userId();
        rateLimiter.consume(SEND_BUCKET, senderId.toString(), properties.sendLimit());
        String clean = MessageSanitizer.clean(rawBody);
        if (clean.isEmpty()) {
            throw new ApiProblemException(ChatProblem.EMPTY_MESSAGE);
        }
        if (clean.codePointCount(0, clean.length()) > ChatProperties.MAX_MESSAGE_LENGTH) {
            throw new ApiProblemException(ChatProblem.MESSAGE_TOO_LONG);
        }
        Redaction redaction = ContactRedactor.redact(clean);
        List<Label> labels = classifier.classify(clean, redaction.redacted());
        String stored = redaction.text();

        return transactions.execute(status -> {
            accounts.lockActive(caller);
            Conversation conversation = conversations.findByIdForUpdate(conversationId)
                    .filter(found -> found.includes(senderId))
                    .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));

            Optional<ChatMessage> earlier = messages.findByConversationIdAndSenderIdAndClientMessageId(
                    conversationId, senderId, clientMessageId);
            if (earlier.isPresent()) {
                MessageView view = reader.view(earlier.get(), senderId);
                if (!stored.equals(view.body())) {
                    throw new ApiProblemException(ChatProblem.MESSAGE_ID_REUSED);
                }
                return new Sent(view, true);
            }

            UUID recipientId = conversation.otherThan(senderId);
            if (!conversation.isActive() || !queries.isActiveUser(recipientId)) {
                throw new ApiProblemException(ChatProblem.CONVERSATION_CLOSED);
            }

            Instant now = clock.instant();
            long seq = conversation.nextSeq(senderId, now);
            byte[] ciphertext = cipher.seal(senderId, EncryptedField.CHAT_MESSAGE,
                    ChatMessage.encryptionContext(conversationId, seq), stored.getBytes(StandardCharsets.UTF_8));
            FlagType flag = labels.isEmpty() ? null : labels.getFirst().type();
            ChatMessage message = messages.save(ChatMessage.fromUser(conversationId, seq, senderId, clientMessageId,
                    ciphertext, redaction.redacted(), flag, now));
            recordFlags(message, senderId, labels, now);
            conversations.save(conversation);

            MessageView forSender = MessageReader.view(message, senderId, stored);
            MessageView forRecipient = MessageReader.view(message, recipientId, stored);
            long recipientUnread = messages.countUnread(conversationId, recipientId,
                    conversation.readSeqOf(recipientId));
            events.publishEvent(new MessagePosted(conversationId, senderId, recipientId, forSender, forRecipient,
                    recipientUnread));
            return new Sent(forSender, false);
        });
    }

    private void recordFlags(ChatMessage message, UUID senderId, List<Label> labels, Instant now) {
        BigDecimal penaltyAt = BigDecimal.valueOf(properties.penaltyConfidence());
        for (Label label : labels) {
            boolean penalty = label.confidence().compareTo(penaltyAt) >= 0;
            flags.save(ModerationFlag.raise(message.getId(), label.type(), label.confidence(),
                    penalty ? AutoAction.TRUST_SCORE_PENALTY : AutoAction.NONE, now));
            if (penalty) {
                trustEvents.messageFlagged(senderId, message.getId(), label.type(), label.confidence());
            }
        }
    }
}
