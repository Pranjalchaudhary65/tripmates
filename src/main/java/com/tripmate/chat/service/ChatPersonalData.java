package com.tripmate.chat.service;

import com.tripmate.chat.entity.ChatMessage;
import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import com.tripmate.platform.privacy.PersonalDataContributor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The messages a user sent, decrypted, in a data export. Messages other
 * people sent are theirs, sealed under their keys, and are not included.
 */
@Component
public class ChatPersonalData implements PersonalDataContributor {

    private final NamedParameterJdbcTemplate jdbc;

    private final UserDataCipher cipher;

    public ChatPersonalData(NamedParameterJdbcTemplate jdbc, UserDataCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public String section() {
        return "messages";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Object export(UUID userId) {
        List<Map<String, Object>> sent = jdbc.query("""
                SELECT conversation_id, seq, body_ciphertext, created_at::text AS created_at
                FROM chat_messages WHERE sender_id = :user AND body_ciphertext IS NOT NULL
                ORDER BY created_at, seq""", new MapSqlParameterSource("user", userId), (rs, row) -> {
            UUID conversation = rs.getObject("conversation_id", UUID.class);
            long seq = rs.getLong("seq");
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("conversationId", conversation.toString());
            message.put("sentAt", rs.getString("created_at"));
            message.put("body", cipher.open(userId, EncryptedField.CHAT_MESSAGE,
                            ChatMessage.encryptionContext(conversation, seq), rs.getBytes("body_ciphertext"))
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null));
            return message;
        });
        return Map.of("sent", sent);
    }
}