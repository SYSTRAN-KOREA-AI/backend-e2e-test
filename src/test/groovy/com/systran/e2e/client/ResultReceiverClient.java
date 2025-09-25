package com.systran.e2e.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ResultReceiverClient {

    private final String userName;
    private final String language;
    private final String jwtToken;
    private final BlockingQueue<Map> transcriptionQueue;
    @Getter
    private final BlockingQueue<Map> translationQueue;

    private final WebSocketStompClient stompClient;
    private StompSession stompSession;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CountDownLatch subscribeLatch;

    public ResultReceiverClient(String userName, String language, String jwtToken, BlockingQueue<Map> transcriptionQueue, BlockingQueue<Map> translationQueue) {
        this.userName = userName;
        this.language = language;
        this.jwtToken = jwtToken;
        this.transcriptionQueue = transcriptionQueue;
        this.translationQueue = translationQueue;

        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        this.stompClient = new WebSocketStompClient(new SockJsClient(transports));
        this.stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        this.subscribeLatch = new CountDownLatch(translationQueue == null ? 1 : 2);
    }

    public void connect(String uri, String meetingId) {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + jwtToken);
        connectHeaders.add("userName", userName);
        connectHeaders.add("language", language);
        connectHeaders.add("meetingId", meetingId);

        this.stompClient.connectAsync(uri, (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                log.info("ResultReceiverClient ({}): Connected to text-retriever. Session: {}", userName, session);
                stompSession = session;

                // Subscribe for transcription, accepting raw bytes and parsing manually
                session.subscribe("/topic/transcription." + meetingId, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        try {
                            log.info("ResultReceiverClient ({}): Transcription received: {}", userName, new String((byte[]) payload));
                            transcriptionQueue.add(objectMapper.readValue((byte[]) payload, Map.class));
                        } catch (Exception e) {
                            log.error("ResultReceiverClient ({}): Failed to parse transcription payload", userName, e);
                        }
                    }
                });
                subscribeLatch.countDown();

                // Subscribe for translation if needed, also accepting raw bytes
                if (translationQueue != null) {
                    session.subscribe("/user/queue/translations", new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return byte[].class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            try {
                                log.info("ResultReceiverClient ({}): Translation received: {}", userName, new String((byte[]) payload));
                                translationQueue.add(objectMapper.readValue((byte[]) payload, Map.class));
                            } catch (Exception e) {
                                log.error("ResultReceiverClient ({}): Failed to parse translation payload", userName, e);
                            }
                        }
                    });
                    subscribeLatch.countDown();
                }
            }

            @Override
            public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable ex) {
                log.error("ResultReceiverClient ({}): STOMP Exception", userName, ex);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                log.error("ResultReceiverClient ({}): STOMP TransportError", userName, exception);
            }
        });
    }

    public boolean isReady(long timeoutSeconds) throws InterruptedException {
        return subscribeLatch.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    public void close() {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
        }
    }
}