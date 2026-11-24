package com.tripmate.chat.websocket;

import com.tripmate.chat.service.ChatProperties;
import com.tripmate.platform.security.CorsProperties;
import com.tripmate.platform.web.ApiPaths;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over a plain WebSocket at {@value #ENDPOINT}, no SockJS fallback.
 *
 * <p>The handshake accepts only the browser origins the API's CORS allowlist
 * names, which closes cross-site WebSocket hijacking even before the CONNECT
 * frame's token is checked. The broker is Spring's in-memory one, carrying
 * only {@code /queue} destinations; clients reach their own queue through
 * {@code /user}, and send to {@code /app}, as {@link StompAuthChannelInterceptor}
 * enforces.
 *
 * <p>Frames are small by design (typing notices; messages go over HTTP), so
 * the size limit is low, and a slow client cannot pile up more than a fixed
 * send buffer before its connection is dropped.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = ApiPaths.V1 + "/ws";

    private static final int SEND_BUFFER_BYTES = 256 * 1024;

    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    private final StompAuthChannelInterceptor interceptor;

    private final ChatConnections connections;

    private final CorsProperties cors;

    private final ChatProperties properties;

    public ChatWebSocketConfig(StompAuthChannelInterceptor interceptor, ChatConnections connections,
                               CorsProperties cors, ChatProperties properties) {
        this.interceptor = interceptor;
        this.connections = connections;
        this.cors = cors;
        this.properties = properties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT).setAllowedOrigins(cors.allowedOrigins().toArray(String[]::new));
        registry.setErrorHandler(new ChatStompErrorHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(properties.socket().maxFrameBytes());
        registration.setSendBufferSizeLimit(SEND_BUFFER_BYTES);
        registration.setSendTimeLimit(SEND_TIME_LIMIT_MILLIS);
        registration.setTimeToFirstMessage((int) properties.socket().connectTimeout().toMillis());
        registration.addDecoratorFactory(connections::decorate);
    }
}
