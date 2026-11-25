package com.tripmate.chat.repository;

import com.tripmate.chat.entity.ConversationStatus;
import com.tripmate.chat.entity.FlagType;
import com.tripmate.chat.entity.MessageKind;
import com.tripmate.chat.entity.SystemCode;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.trips.entity.TripStatus;
import com.tripmate.vibe.entity.BudgetBand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The inbox and conversation header reads, one query each. The trip comes
 * from {@code trip_facts} and the other person from
 * {@code user_public_facts}, and the match from {@code trip_member_facts}, the
 * views trips and identity publish; nothing here reads {@code trips},
 * {@code trip_members} or {@code users}. Every query is narrowed to
 * conversations the viewer is in, in SQL, so a row about anyone else's
 * conversation is never loaded at all.
 */
@Repository
public class ChatQueries {

    private static final String COLUMNS = """
            c.id, c.status, c.contact_unlocked_at, c.last_message_at, c.created_at, c.last_seq,
            c.host_user_id = :viewer AS viewer_is_host, mf.member_id AS match_id,
            CASE WHEN c.host_user_id = :viewer THEN c.requester_user_id ELSE c.host_user_id END AS other_user_id,
            t.trip_id, t.title, t.destination_name, t.start_date, t.end_date, t.budget_band, t.status AS trip_status,
            o.first_name AS other_first_name, o.id_verification_tier AS other_tier, o.active AS other_active,
            (SELECT count(*) FROM chat_messages u
             WHERE u.conversation_id = c.id AND u.kind = 'USER' AND u.sender_id <> :viewer
               AND u.seq > CASE WHEN c.host_user_id = :viewer THEN c.host_read_seq ELSE c.requester_read_seq END)
                AS unread,
            lm.seq AS last_message_seq, lm.kind AS last_message_kind, lm.sender_id AS last_message_sender,
            lm.body_ciphertext AS last_message_body, lm.system_code AS last_message_system_code,
            lm.flag_reason AS last_message_flag""";

    private static final String FROM = """
            FROM conversations c
            JOIN trip_facts t ON t.trip_id = c.trip_id
            JOIN user_public_facts o
              ON o.user_id = CASE WHEN c.host_user_id = :viewer THEN c.requester_user_id ELSE c.host_user_id END
            LEFT JOIN chat_messages lm ON lm.conversation_id = c.id AND lm.seq = c.last_seq
            LEFT JOIN trip_member_facts mf ON mf.trip_id = c.trip_id AND mf.user_id = c.requester_user_id""";

    private static final String VIEWER_IN = "(c.host_user_id = :viewer OR c.requester_user_id = :viewer)";

    private final NamedParameterJdbcTemplate jdbc;

    public ChatQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The viewer's conversations, the most recently active first. */
    public Page<ConversationCard> inbox(UUID viewer, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("viewer", viewer)
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        Long total = jdbc.queryForObject("SELECT count(*) FROM conversations c WHERE " + VIEWER_IN, params,
                Long.class);
        List<ConversationCard> cards = jdbc.query("SELECT " + COLUMNS + "\n" + FROM + "\nWHERE " + VIEWER_IN
                + "\nORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC, c.id\nLIMIT :limit OFFSET :offset",
                params, (rs, row) -> card(rs));
        return new PageImpl<>(cards, pageable, total == null ? 0 : total);
    }

    /** One conversation, only if the viewer is in it; empty for every other ID. */
    public Optional<ConversationCard> find(UUID viewer, UUID conversationId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("viewer", viewer)
                .addValue("id", conversationId);
        return jdbc.query("SELECT " + COLUMNS + "\n" + FROM + "\nWHERE c.id = :id AND " + VIEWER_IN, params,
                (rs, row) -> card(rs)).stream().findFirst();
    }

    /** Whether either of the two has blocked the other, from the view identity publishes. */
    public boolean blockedBetween(UUID one, UUID other) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_block_facts WHERE user_id = :one AND other_user_id = :other)""",
                new MapSqlParameterSource("one", one).addValue("other", other), Boolean.class));
    }

    /** Whether the account is still active; false for one that does not exist. */
    public boolean isActiveUser(UUID userId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT active FROM user_public_facts WHERE user_id = :id",
                new MapSqlParameterSource("id", userId), rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE));
    }

    private static ConversationCard card(ResultSet rs) throws SQLException {
        long lastSeq = rs.getLong("last_message_seq");
        LastMessage last = rs.wasNull() ? null : new LastMessage(lastSeq,
                MessageKind.valueOf(rs.getString("last_message_kind")),
                rs.getObject("last_message_sender", UUID.class),
                rs.getBytes("last_message_body"),
                enumOrNull(SystemCode.class, rs.getString("last_message_system_code")),
                enumOrNull(FlagType.class, rs.getString("last_message_flag")));
        return new ConversationCard(
                rs.getObject("id", UUID.class),
                ConversationStatus.valueOf(rs.getString("status")),
                rs.getBoolean("viewer_is_host"),
                rs.getObject("match_id", UUID.class),
                rs.getObject("other_user_id", UUID.class),
                new TripFacts(rs.getObject("trip_id", UUID.class), rs.getString("title"),
                        rs.getString("destination_name"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class), BudgetBand.valueOf(rs.getString("budget_band")),
                        TripStatus.valueOf(rs.getString("trip_status"))),
                new OtherPerson(rs.getString("other_first_name"),
                        VerificationTier.valueOf(rs.getString("other_tier")), rs.getBoolean("other_active")),
                instant(rs, "contact_unlocked_at"),
                rs.getLong("unread"),
                instant(rs, "last_message_at"),
                instant(rs, "created_at"),
                last);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    /**
     * One conversation as the viewer sees it.
     *
     * @param otherUserId for the service's decisions (whose key opens the last
     *                    message, whose contact to reveal); never returned to a client
     */
    public record ConversationCard(UUID id, ConversationStatus status, boolean viewerIsHost, UUID matchId,
                                   UUID otherUserId,
                                   TripFacts trip, OtherPerson other, Instant contactUnlockedAt, long unreadCount,
                                   Instant lastMessageAt, Instant createdAt, LastMessage lastMessage) {

        /** Open for new messages: not closed, and the other person's account still active. */
        public boolean isOpen() {
            return status == ConversationStatus.ACTIVE && other.active();
        }
    }

    public record TripFacts(UUID id, String title, String destinationName, LocalDate startDate, LocalDate endDate,
                            BudgetBand budgetBand, TripStatus status) {
    }

    public record OtherPerson(String firstName, VerificationTier verificationTier, boolean active) {
    }

    /** @param senderId null for a SYSTEM message; server-side only */
    public record LastMessage(long seq, MessageKind kind, UUID senderId, byte[] bodyCiphertext,
                              SystemCode systemCode, FlagType flagReason) {
    }
}
