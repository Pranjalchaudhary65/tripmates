package com.tripmate.chat.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param sendLimit           messages sent, per account
 * @param readLimit           inbox, conversation, history and icebreaker reads, per account
 * @param classifier          which {@link MessageClassifier} labels messages
 * @param icebreakers         which {@link IcebreakerGenerator} suggests opening lines
 * @param penaltyConfidence   a label at least this sure feeds the trust score;
 *                            weaker ones only warn and queue for review
 * @param socket              limits on the WebSocket connection
 */
@ConfigurationProperties("tripmate.chat")
public record ChatProperties(RateLimit sendLimit, RateLimit readLimit, String classifier, String icebreakers,
                             double penaltyConfidence, Socket socket) {

    /** The longest message kept, in characters (code points) after cleaning. */
    public static final int MAX_MESSAGE_LENGTH = 2000;

    public ChatProperties {
        Objects.requireNonNull(sendLimit, "tripmate.chat.send-limit is required");
        Objects.requireNonNull(readLimit, "tripmate.chat.read-limit is required");
        Objects.requireNonNull(socket, "tripmate.chat.socket is required");
        if (penaltyConfidence <= 0 || penaltyConfidence > 1) {
            throw new IllegalArgumentException("tripmate.chat.penalty-confidence must be in (0, 1]");
        }
    }

    /**
     * @param frameLimit        frames one connection may send (subscribes and
     *                          typing notices), counted in memory per connection
     * @param maxFrameBytes     the largest STOMP frame accepted; typing notices are tiny
     * @param connectTimeout    how long an opened socket may wait before its
     *                          STOMP CONNECT has authenticated it
     * @param sweepInterval     how often open connections are checked for an
     *                          expired token or an ended session
     */
    public record Socket(RateLimit frameLimit, int maxFrameBytes, Duration connectTimeout, Duration sweepInterval) {

        public Socket {
            Objects.requireNonNull(frameLimit, "tripmate.chat.socket.frame-limit is required");
            Objects.requireNonNull(connectTimeout, "tripmate.chat.socket.connect-timeout is required");
            Objects.requireNonNull(sweepInterval, "tripmate.chat.socket.sweep-interval is required");
            if (maxFrameBytes < 1024) {
                throw new IllegalArgumentException("tripmate.chat.socket.max-frame-bytes must be at least 1024");
            }
        }
    }
}
