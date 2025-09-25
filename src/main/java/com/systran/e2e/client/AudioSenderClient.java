package com.systran.e2e.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Voice-Gateway와 순수 WebSocket(JSR-356) 통신을 하는 클라이언트.
 * 1. 연결 (Connect)
 * 2. 인증 (Authorize)
 * 3. 등록 (Register)
 * 4. 오디오 데이터 전송 (Send Audio)
 */
@ClientEndpoint
@Getter
@Slf4j
public class AudioSenderClient {

    private static final int CHUNK_SIZE = 2048; // Mimics the frontend (512 samples * 4 bytes/sample)
    private final ClientManager clientManager;
    private final String userName;
    private final String jwtToken;
    private Session session;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 비동기 작업(연결 및 등록)의 완료를 기다리기 위한 동기화 도구
    private final CountDownLatch registerLatch = new CountDownLatch(1);

    private final String transcriptionLanguage;

    public AudioSenderClient(ClientManager clientManager, String userName, String jwtToken, String transcriptionLanguage) {
        this.clientManager = clientManager;
        this.userName = userName;
        this.jwtToken = jwtToken;
        this.transcriptionLanguage = transcriptionLanguage;
    }

    /**
     * 지정된 URI로 WebSocket 서버에 연결을 시도한다.
     */
    public void connect(String uri) throws DeploymentException, IOException {
        // 공유된 ClientManager 인스턴스를 사용하여 연결
        clientManager.connectToServer(this, URI.create(uri));
    }

    /**
     * WebSocket 연결이 성공적으로 수립되면 호출된다.
     */
    @OnOpen
    public void onOpen(Session session) throws IOException {
        this.session = session;
        log.info("AudioSenderClient: Connected to voice-gateway. Session: {}", session.getId());

        // 1. 연결 성공 후, 서버에 'Authorization' 메시지를 보내 인증을 시도한다.
        String authPayload = objectMapper.writeValueAsString(Map.of(
                "type", "Authorization",
                "token", jwtToken
        ));
        session.getBasicRemote().sendText(authPayload);
    }

    /**
     * 서버로부터 텍스트 메시지를 수신하면 호출된다.
     * 서버의 응답에 따라 다음 동작을 결정하는 상태 머신 역할을 한다.
     */
    @OnMessage
    public void onMessage(String message){
        try{
            log.info("AudioSenderClient: Received message: {}", message);
            Map<String, String> response = objectMapper.readValue(message, Map.class);

            if ("auth_ok".equals(response.get("type"))) {
                // 2. 인증('auth_ok')에 성공하면, 'Register' 메시지를 보내 사용자 정보를 등록한다.
                String registerPayload = objectMapper.writeValueAsString(Map.of(
                        "type", "Register",
                        "userName", this.userName,
                        "transcriptionLanguage", this.transcriptionLanguage
                ));
                session.getBasicRemote().sendText(registerPayload);
            } else if ("register_ok".equals(response.get("type"))) {
                // 3. 등록('register_ok')까지 성공하면, 오디오를 보낼 준비가 완료된 것이다.
                log.info("AudioSenderClient: User {} registered successfully.", this.userName);
                registerLatch.countDown(); // isReady()에서 대기 중인 스레드를 깨운다.
            }
        }catch (IOException e) {
            log.error("AudioSenderClient: Failed to process or understand message. Ignoring. Error: {}", e.getMessage());
        }
    }

    /**
     * WebSocket 연결이 닫히면 호출된다.
     */
    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        log.info("AudioSenderClient Session: {}: Connection closed: {}", session.getId(), closeReason);
    }

    /**
     * 통신 중 에러가 발생하면 호출된다.
     */
    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("AudioSenderClient Session: {}: An error occurred.", session.getId(), throwable);
    }

    /**
     * 오디오 데이터를 STT 서버가 기대하는 float32 형식으로 변환하고, 청크로 나누어 전송한다.
     */
    public void sendAudio(byte[] audioData) throws IOException, InterruptedException {
        if (this.session != null && this.session.isOpen()) {
            // 1. Skip 44-byte WAV header
            int headerSize = 44;
            if (audioData.length <= headerSize) {
                log.info("AudioSenderClient: Audio data is too short to be a valid WAV file.");
                return;
            }

            // 2. Convert 16-bit PCM bytes to normalized 32-bit float bytes (as per the Python script)
            ShortBuffer shortBuffer = ByteBuffer.wrap(audioData, headerSize, audioData.length - headerSize)
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            float[] floatSamples = new float[shortBuffer.remaining()];
            for (int i = 0; i < floatSamples.length; i++) {
                floatSamples[i] = (float) shortBuffer.get(i) / 32768.0f;
            }

            // Convert float array back to a byte array for sending
            ByteBuffer floatByteBuffer = ByteBuffer.allocate(floatSamples.length * 4);
            floatByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            floatByteBuffer.asFloatBuffer().put(floatSamples);
            byte[] floatBytes = floatByteBuffer.array();

            // 3. Send the float-bytes in chunks
            int offset = 0;
            while (offset + CHUNK_SIZE <= floatBytes.length) {
                ByteBuffer buffer = ByteBuffer.wrap(floatBytes, offset, CHUNK_SIZE);
                this.session.getBasicRemote().sendBinary(buffer);
                offset += CHUNK_SIZE;
                // 한 청크의 재생 시간 = 512 / 16000 = 0.032초 = 32ms
                long chunkIntervalMillis = 32;
                Thread.sleep(chunkIntervalMillis);
            }

            // Handle the final partial chunk by padding it to CHUNK_SIZE
            int remainingBytes = floatBytes.length - offset;
            if (remainingBytes > 0) {
                log.debug("AudioSenderClient: Sending final padded chunk of size {}", remainingBytes);
                ByteBuffer finalChunk = ByteBuffer.allocate(CHUNK_SIZE);
                finalChunk.order(ByteOrder.LITTLE_ENDIAN);
                finalChunk.put(floatBytes, offset, remainingBytes);
                finalChunk.rewind();
                this.session.getBasicRemote().sendBinary(finalChunk);
            }

            log.debug("AudioSenderClient: Finished sending audio.");

            // 4. VAD Status (speaking: false) JSON 메시지 생성
            Map<String, Object> vadMessage = Map.of(
                    "type", "vad_status",
                    "speaking", false
            );
            // 5. Map을 JSON 문자열로 변환
            String vadPayload = objectMapper.writeValueAsString(vadMessage);

            // 6. 텍스트 메시지로 전송
            this.session.getBasicRemote().sendText(vadPayload);
            log.debug("AudioSenderClient: VAD status message sent.");
            Thread.sleep(1000);
        }
    }

    /**
     * 클라이언트가 오디오를 전송할 준비가 되었는지 확인한다.
     * 등록이 완료될 때까지 최대 N초간 대기한다.
     */
    public boolean isReady(long timeoutSeconds) throws InterruptedException {
        return registerLatch.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * WebSocket 연결을 종료한다.
     */
    public void close() throws IOException {
        if (this.session != null && this.session.isOpen()) {
            this.session.close();
        }
    }
}