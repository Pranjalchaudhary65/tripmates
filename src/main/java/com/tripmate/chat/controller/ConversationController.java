package com.tripmate.chat.controller;

import com.tripmate.chat.dto.ChatMapper;
import com.tripmate.chat.dto.ChatMessageResponse;
import com.tripmate.chat.dto.ContactResponse;
import com.tripmate.chat.dto.ConversationDetailResponse;
import com.tripmate.chat.dto.ConversationSummaryResponse;
import com.tripmate.chat.dto.IcebreakersResponse;
import com.tripmate.chat.dto.MessagePageResponse;
import com.tripmate.chat.dto.ReadMarkerRequest;
import com.tripmate.chat.dto.SendMessageRequest;
import com.tripmate.chat.service.ConversationService;
import com.tripmate.chat.service.MessageService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.openapi.OpenApiConfig;
import com.tripmate.platform.web.PageResponse;
import com.tripmate.platform.web.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The masked chat over HTTP: the inbox, one conversation, its history,
 * sending, read markers, icebreakers and, after the trip locks, contact
 * details. New messages also arrive over the socket; sending is HTTP only,
 * so it shares this route's validation, limits and error bodies.
 *
 * <p>No route takes a user ID. A conversation ID the caller is not in gets
 * the same 404 as one that does not exist.
 */
@RestController
@RequestMapping(ConversationController.BASE)
@Tag(name = "Chat", description = "The masked chat between a host and an accepted companion")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or an ended session",
        content = @Content(mediaType = ConversationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "not_found: no such conversation, or not one the caller is in",
        content = @Content(mediaType = ConversationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Too many requests for this account",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = ConversationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Sessions or limits cannot be checked right now",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait"),
        content = @Content(mediaType = ConversationController.PROBLEM_JSON,
                schema = @Schema(implementation = ProblemDetail.class)))
public class ConversationController {

    public static final String BASE = "/conversations";

    public static final String CONVERSATION = "/{conversationId}";

    public static final String MESSAGES = "/{conversationId}/messages";

    public static final String READ = "/{conversationId}/read";

    public static final String ICEBREAKERS = "/{conversationId}/icebreakers";

    public static final String CONTACT = "/{conversationId}/contact";

    static final String PROBLEM_JSON = "application/problem+json";

    private final ConversationService conversations;

    private final MessageService messages;

    private final ChatMapper mapper;

    public ConversationController(ConversationService conversations, MessageService messages, ChatMapper mapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The caller's conversations", description = "Most recently active first, each with its "
            + "unread count and the start of its last message")
    @ApiResponse(responseCode = "200", description = "One page of conversations")
    public PageResponse<ConversationSummaryResponse> inbox(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "" + Paging.DEFAULT_SIZE)
                                                           int size) {
        return PageResponse.of(conversations.inbox(SessionPrincipal.of(token), Paging.of(page, size)),
                mapper::toResponse);
    }

    @GetMapping(CONVERSATION)
    @Operation(summary = "One conversation with its newest messages")
    @ApiResponse(responseCode = "200", description = "The conversation and up to 50 messages, oldest first")
    public ConversationDetailResponse conversation(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                                   @PathVariable UUID conversationId) {
        return mapper.toResponse(conversations.view(SessionPrincipal.of(token), conversationId));
    }

    @GetMapping(MESSAGES)
    @Operation(summary = "Older messages", description = "Messages numbered below before, oldest first")
    @ApiResponse(responseCode = "200", description = "One page of messages")
    public MessagePageResponse history(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID conversationId,
                                       @Parameter(description = "Fetch messages numbered below this; omit for the newest")
                                       @RequestParam(required = false) Long before,
                                       @Parameter(description = "1 to 50") @RequestParam(defaultValue = "50") int size) {
        long beforeSeq = before == null || before < 1 ? Long.MAX_VALUE : before;
        int clamped = Math.max(1, Math.min(size, ConversationService.PAGE_SIZE));
        return mapper.toResponse(conversations.history(SessionPrincipal.of(token), conversationId, beforeSeq,
                clamped));
    }

    @PostMapping(MESSAGES)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a message", description = "Markup is stripped and contact details are replaced with "
            + "[contact hidden] before the message is stored. Retrying with the same clientMessageId returns the "
            + "stored message instead of sending it again.")
    @ApiResponse(responseCode = "201", description = "The message as stored")
    @ApiResponse(responseCode = "400", description = "empty_message, message_too_long, or a malformed request",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "conversation_closed or message_id_reused",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ChatMessageResponse send(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                    @PathVariable UUID conversationId,
                                    @Valid @RequestBody SendMessageRequest request) {
        return mapper.toResponse(messages.send(SessionPrincipal.of(token), conversationId,
                request.clientMessageId(), request.body()).message());
    }

    @PostMapping(READ)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark messages read", description = "Moves the caller's read marker forward; never back")
    @ApiResponse(responseCode = "204", description = "Marked")
    public void markRead(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                         @PathVariable UUID conversationId, @Valid @RequestBody ReadMarkerRequest request) {
        conversations.markRead(SessionPrincipal.of(token), conversationId, request.upToSeq());
    }

    @GetMapping(ICEBREAKERS)
    @Operation(summary = "Suggested opening lines", description = "Built from the trip's facts. Nothing is sent; "
            + "the caller may send one as their own message.")
    @ApiResponse(responseCode = "200", description = "Up to three suggestions")
    public IcebreakersResponse icebreakers(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                           @PathVariable UUID conversationId) {
        return new IcebreakersResponse(conversations.icebreakers(SessionPrincipal.of(token), conversationId));
    }

    @GetMapping(CONTACT)
    @Operation(summary = "The other person's real contact details", description = "Only once the trip has locked "
            + "for this conversation, after the public meet-up")
    @ApiResponse(responseCode = "200", description = "Name, email and, when verified, phone number")
    @ApiResponse(responseCode = "409", description = "contact_locked before the trip locks; conversation_closed "
            + "when the other account is no longer active",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ContactResponse contact(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token,
                                   @PathVariable UUID conversationId) {
        return mapper.toResponse(conversations.contact(SessionPrincipal.of(token), conversationId));
    }
}
