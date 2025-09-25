package com.systran.e2e.loadtest.config;

import org.glassfish.tyrus.client.ClientManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.util.List;

/**
 * WebSocket 클라이언트 객체들을 Spring Bean으로 등록하는 설정 클래스.
 * 이 클래스를 통해 무거운 WebSocket 클라이언트 객체들이 애플리케이션에서 단 한번만 생성(Singleton)되고 재사용됩니다.
 */
@Configuration
public class WebSocketClientConfig {

    /**
     * JSR-356 기반의 순수 WebSocket 클라이언트 매니저를 Bean으로 등록합니다.
     * AudioSenderClient가 사용합니다.
     * @return 공유될 ClientManager 인스턴스
     */
    @Bean
    public ClientManager tyrusClientManager() {
        return ClientManager.createClient();
    }

    /**
     * STOMP over WebSocket 클라이언트를 Bean으로 등록합니다.
     * ResultReceiverClient가 사용합니다.
     * @return 공유될 WebSocketStompClient 인스턴스
     */
    @Bean
    public WebSocketStompClient stompClient() {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        return stompClient;
    }
}
