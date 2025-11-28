package com.systran.e2e.loadtest.controller;

import com.systran.e2e.client.AudioSenderClient;
import com.systran.e2e.client.ResultReceiverClient;
import com.systran.e2e.loadtest.controller.model.LoadTestRequest;
import org.glassfish.tyrus.client.ClientManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 부하 테스트를 실행하기 위한 API 엔드포인트를 제공
 */
@RestController
public class LoadTestController {

    @Value("${test.service.voice-gateway.uri}")
    private String VOICE_GATEWAY_BASE_URI;
    @Value("${test.service.text-retriever.uri}")
    private String TEXT_RETRIEVER_URI;
    @Value("${test.audio-file-path}")
    private String AUDIO_FILE_PATH;
    @Value("${test.jwt.token}")
    private String ACCESS_TOKEN;

    @Autowired
    private ClientManager tyrusClientManager;

    @Autowired
    private WebSocketStompClient stompClient;

    @GetMapping
    public ResponseEntity<String> home() {
        return ResponseEntity.ok("Hello World!!!");
    }

    /**
     * 부하 테스트 시나리오를 실행하는 메인 엔드포인트입니다.
     * @param request 요청 본문으로, 청취자 수(listenerCount)를 포함합니다.
     * @return 테스트 결과(성공 여부, Latency 등)를 담은 ResponseEntity 객체
     */
    @PostMapping("/run-test")
    public ResponseEntity<Map<String, Object>> runTest(@RequestBody LoadTestRequest request) {
        long scenarioStartTime = System.currentTimeMillis();
        String scenarioId = getUUID(4);
        String meetingId = "BM_ID-" + scenarioId;
        //String accessToken = tokenHelper.getAccessToken();
        int totalUsers = request.getListenerCount();

        // 오디오 파일을 미리 로드합니다.
        byte[] audioBytes;
        try (InputStream audioStream = getClass().getClassLoader().getResourceAsStream(AUDIO_FILE_PATH)) {
            if (audioStream == null) {
                return ResponseEntity.status(500).body(Map.of("error", "Audio file not found: " + AUDIO_FILE_PATH));
            }
            audioBytes = audioStream.readAllBytes();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to read audio file", "details", e.getMessage()));
        }

        // 1명의 발화자와 N명의 청취자 리스트를 준비합니다.
        List<AudioSenderClient> audioSenders = new ArrayList<>();
        List<ResultReceiverClient> resultReceivers = new ArrayList<>();
        List<CompletableFuture<Void>> connectionFutures = new ArrayList<>();
        String speakerName = "speaker-" + scenarioId;

        // 모든 참여자(발화자 + 청취자) 클라이언트 생성 및 연결
        for (int i = 0; i < totalUsers; i++) {
            String userName = (i == 0) ? speakerName : "listener-" + (i - 1) + "-" + scenarioId;
            String lang = "ko"; // 언어 설정

            // 1. 모든 참여자에 대해 AudioSenderClient(Voice-Gateway) 생성
            AudioSenderClient audioSender = new AudioSenderClient(tyrusClientManager, userName, ACCESS_TOKEN, lang);
            audioSenders.add(audioSender);
            connectionFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    audioSender.connect(VOICE_GATEWAY_BASE_URI + meetingId);
                    if (!audioSender.isReady(15)) throw new RuntimeException(userName + " (AudioSender) failed to get ready.");
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }));

            // 2. 모든 참여자에 대해 ResultReceiverClient(Text-Retriever) 생성
            BlockingQueue<Map> transcriptionQueue = new LinkedBlockingQueue<>();
            ResultReceiverClient resultReceiver = new ResultReceiverClient(stompClient, userName, lang, ACCESS_TOKEN, transcriptionQueue, null);
            resultReceivers.add(resultReceiver);
            connectionFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    resultReceiver.connect(TEXT_RETRIEVER_URI, meetingId);
                    if (!resultReceiver.isReady(15)) throw new RuntimeException(userName + " (ResultReceiver) failed to get ready.");
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }));
        }

        try {
            // 모든 클라이언트가 연결되고 준비될 때까지 대기
            CompletableFuture.allOf(connectionFutures.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Clients connection failed", "details", e.getMessage()));
        }

        long audioSendStartTime = System.currentTimeMillis();

        // 발화자가 오디오 전송 시작 (리스트의 첫 번째 클라이언트가 발화자)
        try {
            AudioSenderClient speaker = audioSenders.getFirst();
            speaker.sendAudio(audioBytes);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send audio", "details", e.getMessage()));
        }

        // 모든 청취자가 발화의 최종 전사 결과를 받을 때까지 대기
        final CountDownLatch finalMessageLatch = new CountDownLatch(resultReceivers.size());
        for (ResultReceiverClient receiver : resultReceivers) {
            new Thread(() -> {
                while (true) {
                    try {
                        Map msg = receiver.getTranscriptionQueue().poll(60, TimeUnit.SECONDS);
                        if (msg != null && speakerName.equals(msg.get("userName")) && (Boolean) msg.get("isFullText")) {
                            finalMessageLatch.countDown();
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }).start();
        }

        long latency = -1;
        try {
            if (!finalMessageLatch.await(60, TimeUnit.SECONDS)) {
                return ResponseEntity.status(508).body(Map.of("error", "Timed out waiting for final messages"));
            }
            latency = System.currentTimeMillis() - audioSendStartTime;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of("error", "Interrupted while waiting for final messages"));
        }

        // 모든 클라이언트 연결 종료
        audioSenders.forEach(client -> {
            try {
                client.close();
            } catch (Exception e) { /* ignore */ }
        });
        resultReceivers.forEach(ResultReceiverClient::close);


        long totalScenarioTime = System.currentTimeMillis() - scenarioStartTime;

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "meetingId", meetingId,
                "totalUsers", totalUsers,
                "speakers", 1,
                "e2e_latency_ms", latency,
                "total_scenario_time_ms", totalScenarioTime
        ));
    }

    private String getUUID(int n) {
        return UUID.randomUUID().toString().substring(0, n);
    }
}
