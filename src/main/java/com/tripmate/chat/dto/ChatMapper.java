package com.tripmate.chat.dto;

import com.tripmate.chat.repository.ChatQueries.ConversationCard;
import com.tripmate.chat.repository.ChatQueries.LastMessage;
import com.tripmate.chat.service.ChatViews.ConversationView;
import com.tripmate.chat.service.ChatViews.InboxItem;
import com.tripmate.chat.service.ChatViews.MessagePage;
import com.tripmate.chat.service.ChatViews.MessageView;
import com.tripmate.identity.service.ContactDirectory.Contact;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Read models to responses, field by field. The other person's user ID has no field to land in. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ChatMapper {

    ChatMessageResponse toResponse(MessageView message);

    MessagePageResponse toResponse(MessagePage page);

    ContactResponse toResponse(Contact contact);

    default ConversationSummaryResponse toResponse(InboxItem item) {
        ConversationCard card = item.card();
        LastMessage last = card.lastMessage();
        return new ConversationSummaryResponse(card.id(), card.matchId(),
                new ConversationSummaryResponse.Trip(card.trip().id(), card.trip().title(),
                        card.trip().destinationName(), card.trip().startDate(), card.trip().endDate(),
                        card.trip().status()),
                new ConversationSummaryResponse.Person(card.other().firstName(), card.other().verificationTier()),
                card.viewerIsHost() ? ConversationSummaryResponse.Role.HOST : ConversationSummaryResponse.Role.COMPANION,
                card.isOpen(), card.contactUnlockedAt() != null, card.unreadCount(), card.lastMessageAt(),
                last == null ? null : new ConversationSummaryResponse.LastMessage(last.seq(), last.kind(),
                        item.lastMessageMine(), item.lastMessagePreview(), last.systemCode(), last.flagReason()),
                card.createdAt());
    }

    default ConversationDetailResponse toResponse(ConversationView view) {
        MessagePageResponse page = toResponse(view.messages());
        return new ConversationDetailResponse(toResponse(view.conversation()), page.items(), page.hasOlder());
    }
}
