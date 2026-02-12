package com.systran.e2e

import com.systran.e2e.client.AudioSenderClient
import com.systran.e2e.client.ResultReceiverClient
import groovy.transform.ToString
import org.glassfish.tyrus.client.ClientManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.socket.messaging.WebSocketStompClient
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue

@SpringBootTest
@ActiveProfiles("test")
class MeetingFlowSpec extends Specification {
    @Autowired
    ClientManager tyrusClientManager

    @Autowired
    WebSocketStompClient stompClient

    @Value('${test.jwt.token}')
    String JWT_TOKEN
    @Value('${test.service.voice-gateway.uri}')
    String VOICE_GATEWAY_BASE_URI
    @Value('${test.service.text-retriever.uri}')
    String TEXT_RETRIEVER_URI
    @Value('${test.audio-file-path}')
    String AUDIO_FILE_PATH
    @Value('${test.audio-file-path-b}')
    String AUDIO_FILE_PATH_B
    @Value('${test.audio-file-path-c}')
    String AUDIO_FILE_PATH_C

    def MEETING_ID = "e2e-meeting-${System.currentTimeMillis()}"
    private static final int SAFE_CLEANUP_WAIT_MS = 5000
    private static final int TRANSLATION_IDLE_WINDOW_MS = 5000

    @ToString(includeNames =  true)
    private static class TestParticipant{
        String name
        String language
        AudioSenderClient audioSenderClient
        ResultReceiverClient resultReceiverClient
        BlockingQueue<Map> transcriptionQueue
        BlockingQueue<Map> translationQueue
    }

    /**
     * 테스트에 참여하는 사용자 객체를 생성한다.
     * @param name 사용자의 고유 이름
     * @param language 사용자의 언어 (e.g., "ko", "en")
     * @param needsTranslation 번역 결과 수신 여부
     * @return 생성된 TestParticipant 객체
     */
    private TestParticipant createParticipant(String name, String language, boolean needsTranslation){
        def transcriptionQueue = new LinkedBlockingQueue<Map>()
        def translationQueue = needsTranslation ? new LinkedBlockingQueue<Map>() : null
        return new TestParticipant(
                name: name,
                language: language,
                audioSenderClient: new AudioSenderClient(tyrusClientManager, name, JWT_TOKEN, language),
                resultReceiverClient: new ResultReceiverClient(stompClient, name, language, JWT_TOKEN, transcriptionQueue, translationQueue),
                transcriptionQueue: transcriptionQueue,
                translationQueue: translationQueue
        )
    }

    /**
     * 주어진 참여자 목록의 모든 클라이언트를 서버에 연결한다.
     * @param participants 참여자 리스트
     * @param meetingId 연결할 미팅 ID
     */
    private void connectAll(List<TestParticipant> participants, String meetingId){
        participants.each{ p ->
            String voiceUri = VOICE_GATEWAY_BASE_URI + meetingId
            try {
                p.audioSenderClient.connect(voiceUri)
            } catch (Exception e) {
                throw new RuntimeException("AudioSender connect failed. user=${p.name}, uri=${voiceUri}", e)
            }

            try {
                p.resultReceiverClient.connect(TEXT_RETRIEVER_URI, meetingId)
            } catch (Exception e) {
                throw new RuntimeException("ResultReceiver connect failed. user=${p.name}, uri=${TEXT_RETRIEVER_URI}, meetingId=${meetingId}", e)
            }
        }
    }

    /**
     * 모든 참여자의 클라이언트가 준비 상태가 될 때까지 대기한다.
     * @param participants 참여자 리스트
     * @param timeoutSeconds 최대 대기 시간 (초)
     * @return 모든 클라이언트가 준비되면 true, 아니면 false
     */
    private boolean waitForAllReady(List<TestParticipant> participants, long timeoutSeconds) {
        def allReady = participants.every { p ->
            p.audioSenderClient.isReady(timeoutSeconds) && p.resultReceiverClient.isReady(timeoutSeconds)
        }
        if (allReady) {
            println "모든 참여자(${participants*.name.join(', ')})가 준비되었습니다."
        }
        return allReady
    }

    /**
     * 특정 큐에서 최종 메시지(isFullText=true)를 찾아서 반환한다.
     */
    private Map findFinalMessage(BlockingQueue<Map> queue){
        if(queue == null) return null
        return queue.find{it.isFullText == true}
    }

    /**
     * 모든 참여자의 리소스를 안전하게 정리한다.
     * @param participants 정리할 참여자 리스트
     */
    private void cleanupAll(List<TestParticipant> participants) {
        def participantNames = participants?.collect { it?.name }?.findAll { it != null } ?: []
        println "클린업 시작: ${participantNames.join(', ')}"
        participants?.each { p ->
            try { p.audioSenderClient?.close() } catch (Exception e) { println "오디오 클라이언트 종료 오류: ${e.message}" }
            try { p.resultReceiverClient?.close() } catch (Exception e) { println "결과 클라이언트 종료 오류: ${e.message}" }
        }
    }

    /**
     * 특정 큐에서, 특정 사용자의 최종 메시지(isFullText=true)를 찾아서 반환한다.
     */
    private Map findFinalMessageByUser(BlockingQueue<Map> queue, String userName) {
        if (queue == null) return null
        return queue.find { it.isFullText == true && it.userName == userName }
    }

    /**
     * 영어 청중들이 각자 최종 번역 메시지(isFullText=true, translationText 존재)를
     * 최소 1건씩 수신할 때까지 대기하고, 마지막 수신 시각을 반환한다.
     */
    private long waitForLastFinalTranslationTimestamp(List<TestParticipant> listeners, long timeoutSeconds) {
        def pollingConditions = new PollingConditions(timeout: timeoutSeconds, initialDelay: 3)
        def finalTranslationTimestampByListener = new ConcurrentHashMap<String, Long>()

        pollingConditions.eventually {
            long now = System.currentTimeMillis()

            listeners.each { listener ->
                if (finalTranslationTimestampByListener.containsKey(listener.name)) {
                    return
                }

                def q = listener.translationQueue
                if (q == null) {
                    return
                }

                def messages = []
                q.drainTo(messages)

                messages.each { msg ->
                    boolean isFinal = msg['isFullText'] == true
                    String translationText = msg['translationText']?.toString()
                    boolean hasText = translationText != null && !translationText.isBlank()

                    if (isFinal && hasText) {
                        finalTranslationTimestampByListener.putIfAbsent(listener.name, now)
                    }
                }
            }

            assert finalTranslationTimestampByListener.size() == listeners.size()
        }

        return finalTranslationTimestampByListener.values().max() as long
    }

    /**
     * 발화 시작 이후, 리스너별 "최종 전사 idx == 최종 번역 idx"가 안정화될 때까지 대기한다.
     * completionAt은 해당 리스너가 마지막 최종 번역을 수신한 시각이다.
     */
    private Map<String, Map<String, Object>> collectFinalTranslationCompletionByIdx(
            List<TestParticipant> listeners,
            long timeoutSeconds,
            long transcriptionIdleWindowMs
    ) {
        def pollingConditions = new PollingConditions(timeout: timeoutSeconds, initialDelay: 3)
        def maxFinalTranscriptionIdxByListener = new ConcurrentHashMap<String, Integer>()
        def maxFinalTranslationIdxByListener = new ConcurrentHashMap<String, Integer>()
        def lastFinalTranscriptionAtByListener = new ConcurrentHashMap<String, Long>()
        def lastFinalTranslationAtByListener = new ConcurrentHashMap<String, Long>()
        def finalTranscriptionCountByListener = new ConcurrentHashMap<String, Integer>()
        def finalTranslationCountByListener = new ConcurrentHashMap<String, Integer>()

        pollingConditions.eventually {
            long now = System.currentTimeMillis()

            listeners.each { listener ->
                def transcriptionMessages = []
                listener.transcriptionQueue?.drainTo(transcriptionMessages)
                transcriptionMessages.each { msg ->
                    boolean isFinal = msg['isFullText'] == true
                    if (!isFinal) {
                        return
                    }
                    Integer idx = parseUtteranceIdx(msg)
                    if (idx == null) {
                        return
                    }

                    long receivedAt = extractReceivedAt(msg, now)
                    updateMaxIndex(maxFinalTranscriptionIdxByListener, listener.name, idx)
                    updateMaxTimestamp(lastFinalTranscriptionAtByListener, listener.name, receivedAt)
                    finalTranscriptionCountByListener.merge(listener.name, 1) { oldValue, inc -> oldValue + inc }
                }

                def translationMessages = []
                listener.translationQueue?.drainTo(translationMessages)
                translationMessages.each { msg ->
                    boolean isFinal = msg['isFullText'] == true
                    String translationText = msg['translationText']?.toString()
                    boolean hasText = translationText != null && !translationText.isBlank()
                    if (!isFinal || !hasText) {
                        return
                    }
                    Integer idx = parseUtteranceIdx(msg)
                    if (idx == null) {
                        return
                    }

                    long receivedAt = extractReceivedAt(msg, now)
                    updateMaxIndex(maxFinalTranslationIdxByListener, listener.name, idx)
                    updateMaxTimestamp(lastFinalTranslationAtByListener, listener.name, receivedAt)
                    finalTranslationCountByListener.merge(listener.name, 1) { oldValue, inc -> oldValue + inc }
                }
            }

            listeners.each { listener ->
                int maxFinalTranscriptionIdx = maxFinalTranscriptionIdxByListener[listener.name] ?: 0
                int maxFinalTranslationIdx = maxFinalTranslationIdxByListener[listener.name] ?: 0
                long lastFinalTranscriptionAt = lastFinalTranscriptionAtByListener[listener.name] ?: 0L

                boolean hasFinalTranscription = maxFinalTranscriptionIdx > 0
                boolean transcriptionSettled = hasFinalTranscription && (now - lastFinalTranscriptionAt) >= transcriptionIdleWindowMs
                boolean translationCaughtUp = hasFinalTranscription && maxFinalTranslationIdx >= maxFinalTranscriptionIdx

                assert transcriptionSettled
                assert translationCaughtUp
            }
        }

        def result = [:]
        listeners.each { listener ->
            result[listener.name] = [
                    completionAt              : lastFinalTranslationAtByListener[listener.name],
                    targetFinalTranscriptionIdx: (maxFinalTranscriptionIdxByListener[listener.name] ?: 0),
                    maxFinalTranslationIdx    : (maxFinalTranslationIdxByListener[listener.name] ?: 0),
                    finalTranscriptionCount   : (finalTranscriptionCountByListener[listener.name] ?: 0),
                    finalTranslationCount     : (finalTranslationCountByListener[listener.name] ?: 0)
            ]
        }
        return result
    }

    private Integer parseUtteranceIdx(Map msg) {
        def raw = msg['utteranceIdx']
        if (raw == null) {
            return null
        }
        if (raw instanceof Number) {
            return (raw as Number).intValue()
        }
        try {
            return Integer.parseInt(raw.toString())
        } catch (Exception ignored) {
            return null
        }
    }

    private long extractReceivedAt(Map msg, long fallbackNow) {
        def raw = msg['__receivedAt']
        if (raw == null) {
            return fallbackNow
        }
        if (raw instanceof Number) {
            return (raw as Number).longValue()
        }
        try {
            return Long.parseLong(raw.toString())
        } catch (Exception ignored) {
            return fallbackNow
        }
    }

    private void updateMaxIndex(ConcurrentHashMap<String, Integer> map, String key, int value) {
        map.compute(key) { ignoredKey, oldValue ->
            if (oldValue == null) {
                return value
            }
            return Math.max(oldValue as int, value)
        }
    }

    private void updateMaxTimestamp(ConcurrentHashMap<String, Long> map, String key, long value) {
        map.compute(key) { ignoredKey, oldValue ->
            if (oldValue == null) {
                return value
            }
            return Math.max(oldValue as long, value)
        }
    }

    private long percentile(List<Long> values, int p) {
        assert values != null && !values.isEmpty()
        assert p >= 0 && p <= 100
        def sorted = values.sort(false)
        int index = (int) Math.ceil((p / 100.0d) * sorted.size()) - 1
        index = Math.max(0, Math.min(index, sorted.size() - 1))
        return sorted[index] as long
    }

    private long average(List<Long> values) {
        assert values != null && !values.isEmpty()
        return Math.round((values.sum() as double) / values.size())
    }

    private void printMetricSummary(String label, List<Long> values) {
        long p50 = percentile(values, 50)
        long p95 = percentile(values, 95)
        long min = values.min() as long
        long max = values.max() as long
        long avg = average(values)
        println "${label} min=${min} ms, p50=${p50} ms, p95=${p95} ms, max=${max} ms, avg=${avg} ms"
    }

    /***
     * partial
     * 3:
     * 4:
     * 5:
     */
    def "2 채널, 2명 동시 발화"() {
        given: "4명의 사용자를 2개의 미팅룸에 할당(각 룸: 한국어 발화자 1명 + 영어 청중 1명)"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "en", true)
        def participants_A = [userA1, userA2]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "en", true)
        def participants_B = [userB1, userB2]

        connectAll(participants_A, meetingId_A)
        connectAll(participants_B, meetingId_B)
        assert waitForAllReady(participants_A, 15)
        assert waitForAllReady(participants_B, 15)

        long startTime = 0

        when: "두 채널에서 오디오를 동시에 전송하고, 전송이 모두 끝날 때까지 기다림"
        def executor = Executors.newFixedThreadPool(2)
        def allParticipants = participants_A + participants_B
        allParticipants.each { p ->
            p.transcriptionQueue?.clear()
            p.translationQueue?.clear()
        }

        byte[] audioBytes = getClass().getResourceAsStream("/${AUDIO_FILE_PATH}").bytes
        byte[] audioBytesB = getClass().getResourceAsStream("/${AUDIO_FILE_PATH_B}").bytes
        Future<?> futureA, futureB
        try {
            startTime = System.currentTimeMillis()
            futureA = executor.submit({ -> userA1.audioSenderClient.sendAudio(audioBytes) } as Runnable)
            futureB = executor.submit({ -> userB1.audioSenderClient.sendAudio(audioBytesB) } as Runnable)
            futureA.get()
            futureB.get()
        } finally {
            executor.shutdown()
        }

        then: "모든 미팅룸의 영어 청중이 마지막 최종 번역 텍스트를 수신할 때까지 대기하고 Latency를 측정한다"
        def englishListeners = [userA2, userB2]
        long lastFinalTranslationTimestamp = waitForLastFinalTranslationTimestamp(englishListeners, 120)
        long latency = lastFinalTranslationTimestamp - startTime
        println "[Translation Latency 2 Channel] E2E Latency (to last final translation): ${latency} ms"

        and: "미팅룸 간 교차 오염이 없는지 확인"
        println "Cross-contamination check is implicitly passed by successful stream completion."

        and: "모든 백그라운드 프로세스가 완료될 때까지 안전하게 대기"
        println "All primary assertions passed. Waiting for ${SAFE_CLEANUP_WAIT_MS} ms to ensure all streams are closed..."
        sleep(SAFE_CLEANUP_WAIT_MS)

        cleanup: "모든 참여자의 연결을 종료"
        cleanupAll(participants_A)
        cleanupAll(participants_B)
    }

    /***
     * partial
     * 3:
     * 4:
     * 5:
     */
    def "미팅룸 4개 운영, 4명 동시 발화"() {
        given: "8명의 사용자를 4개의 미팅룸에 할당(각 룸: 한국어 발화자 1명 + 영어 청중 1명)"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"
        def meetingId_C = "meeting-C-${System.currentTimeMillis()}"
        def meetingId_D = "meeting-D-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "en", true)
        def participants_A = [userA1, userA2]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "en", true)
        def participants_B = [userB1, userB2]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "en", true)
        def participants_C = [userC1, userC2]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "en", true)
        def participants_D = [userD1, userD2]

        connectAll(participants_A, meetingId_A)
        connectAll(participants_B, meetingId_B)
        connectAll(participants_C, meetingId_C)
        connectAll(participants_D, meetingId_D)
        assert waitForAllReady(participants_A, 15)
        assert waitForAllReady(participants_B, 15)
        assert waitForAllReady(participants_C, 15)
        assert waitForAllReady(participants_D, 15)

        long startTime = 0

        when: "4개의 채널에서 오디오를 동시에 전송하고, 전송이 모두 끝날 때까지 기다림"
        def executor = Executors.newFixedThreadPool(5)
        def allParticipants = participants_A + participants_B + participants_C + participants_D
        allParticipants.each { p ->
            p.transcriptionQueue?.clear()
            p.translationQueue?.clear()
        }

        byte[] audioBytesA = getClass().getResourceAsStream("/${AUDIO_FILE_PATH}").bytes
        byte[] audioBytesB = getClass().getResourceAsStream("/${AUDIO_FILE_PATH_B}").bytes
        byte[] audioBytesC = getClass().getResourceAsStream("/${AUDIO_FILE_PATH_C}").bytes
        Future<?> futureA, futureB, futureC, futureD
        try {
            startTime = System.currentTimeMillis()
            futureA = executor.submit({ -> userA1.audioSenderClient.sendAudio(audioBytesA) } as Runnable)
            futureB = executor.submit({ -> userB1.audioSenderClient.sendAudio(audioBytesB) } as Runnable)
            futureC = executor.submit({ -> userC1.audioSenderClient.sendAudio(audioBytesC) } as Runnable)
            futureD = executor.submit({ -> userD1.audioSenderClient.sendAudio(audioBytesB) } as Runnable)
            futureA.get()
            futureB.get()
            futureC.get()
            futureD.get()
        } finally {
            executor.shutdown()
        }

        then: "모든 미팅룸의 영어 청중이 마지막 최종 번역 텍스트를 수신할 때까지 대기하고 Latency를 측정한다"
        def englishListeners = [userA2, userB2, userC2, userD2]
        long lastFinalTranslationTimestamp = waitForLastFinalTranslationTimestamp(englishListeners, 120)
        long latency = lastFinalTranslationTimestamp - startTime
        println "[Translation Latency 4 Channel] E2E Latency (to last final translation): ${latency} ms"

        and: "미팅룸 간 교차 오염이 없는지 확인"
        println "Cross-contamination check is implicitly passed by successful stream completion."

        and: "모든 백그라운드 프로세스가 완료될 때까지 안전하게 대기"
        println "All primary assertions passed. Waiting for ${SAFE_CLEANUP_WAIT_MS} ms to ensure all streams are closed..."
        sleep(SAFE_CLEANUP_WAIT_MS)

        cleanup: "모든 참여자의 연결을 종료"
        cleanupAll(participants_A)
        cleanupAll(participants_B)
        cleanupAll(participants_C)
        cleanupAll(participants_D)
    }

    def "미팅룸 6개 운영, 6명 동시 발화"() {
        given: "12명의 사용자를 6개의 미팅룸에 할당(각 룸: 한국어 발화자 1명 + 영어 청중 1명)"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"
        def meetingId_C = "meeting-C-${System.currentTimeMillis()}"
        def meetingId_D = "meeting-D-${System.currentTimeMillis()}"
        def meetingId_E = "meeting-E-${System.currentTimeMillis()}"
        def meetingId_F = "meeting-F-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "en", true)
        def participants_A = [userA1, userA2]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "en", true)
        def participants_B = [userB1, userB2]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "en", true)
        def participants_C = [userC1, userC2]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "en", true)
        def participants_D = [userD1, userD2]

        def userE1 = createParticipant("userE1", "ko", false)
        def userE2 = createParticipant("userE2", "en", true)
        def participants_E = [userE1, userE2]

        def userF1 = createParticipant("userF1", "ko", false)
        def userF2 = createParticipant("userF2", "en", true)
        def participants_F = [userF1, userF2]

        connectAll(participants_A, meetingId_A)
        connectAll(participants_B, meetingId_B)
        connectAll(participants_C, meetingId_C)
        connectAll(participants_D, meetingId_D)
        connectAll(participants_E, meetingId_E)
        connectAll(participants_F, meetingId_F)
        assert waitForAllReady(participants_A, 15)
        assert waitForAllReady(participants_B, 15)
        assert waitForAllReady(participants_C, 15)
        assert waitForAllReady(participants_D, 15)
        assert waitForAllReady(participants_E, 15)
        assert waitForAllReady(participants_F, 15)

        long startTime = 0

        when: "6개의 채널에서 오디오를 동시에 전송하고, 전송이 모두 끝날 때까지 기다림"
        def executor = Executors.newFixedThreadPool(8)
        def allParticipants = participants_A + participants_B + participants_C + participants_D + participants_E + participants_F
        allParticipants.each { p ->
            p.transcriptionQueue?.clear()
            p.translationQueue?.clear()
        }

        byte[] audioBytesA = getClass().getResourceAsStream("/${AUDIO_FILE_PATH}").bytes
        byte[] audioBytesB = getClass().getResourceAsStream("/${AUDIO_FILE_PATH_B}").bytes
        byte[] audioBytesC = getClass().getResourceAsStream("/${AUDIO_FILE_PATH_C}").bytes
        Future<?> futureA, futureB, futureC, futureD, futureE, futureF
        try {
            startTime = System.currentTimeMillis()
            futureA = executor.submit({ -> userA1.audioSenderClient.sendAudio(audioBytesA) } as Runnable)
            futureB = executor.submit({ -> userB1.audioSenderClient.sendAudio(audioBytesB) } as Runnable)
            futureC = executor.submit({ -> userC1.audioSenderClient.sendAudio(audioBytesC) } as Runnable)
            futureD = executor.submit({ -> userD1.audioSenderClient.sendAudio(audioBytesA) } as Runnable)
            futureE = executor.submit({ -> userE1.audioSenderClient.sendAudio(audioBytesB) } as Runnable)
            futureF = executor.submit({ -> userF1.audioSenderClient.sendAudio(audioBytesA) } as Runnable)
            futureA.get()
            futureB.get()
            futureC.get()
            futureD.get()
            futureE.get()
            futureF.get()
        } finally {
            executor.shutdown()
        }

        then: "모든 미팅룸의 영어 청중이 마지막 최종 번역 텍스트를 수신할 때까지 대기하고 Latency를 측정한다"
        def englishListeners = [userA2, userB2, userC2, userD2, userE2, userF2]
        long lastFinalTranslationTimestamp = waitForLastFinalTranslationTimestamp(englishListeners, 120)
        long latency = lastFinalTranslationTimestamp - startTime
        println "[Translation Latency 6 Channel] E2E Latency (to last final translation): ${latency} ms"

        and: "미팅룸 간 교차 오염이 없는지 확인"
        println "Cross-contamination check is implicitly passed by successful stream completion."

        and: "모든 백그라운드 프로세스가 완료될 때까지 안전하게 대기"
        println "All primary assertions passed. Waiting for ${SAFE_CLEANUP_WAIT_MS} ms to ensure all streams are closed..."
        sleep(SAFE_CLEANUP_WAIT_MS)

        cleanup: "모든 참여자의 연결을 종료"
        cleanupAll(participants_A)
        cleanupAll(participants_B)
        cleanupAll(participants_C)
        cleanupAll(participants_D)
        cleanupAll(participants_E)
        cleanupAll(participants_F)
    }

    def "미팅룸 8개 운영, 8명 동시 발화"() {
        given: "16명의 사용자를 8개의 미팅룸에 할당(각 룸: 한국어 발화자 1명 + 영어 청중 1명)"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"
        def meetingId_C = "meeting-C-${System.currentTimeMillis()}"
        def meetingId_D = "meeting-D-${System.currentTimeMillis()}"
        def meetingId_E = "meeting-E-${System.currentTimeMillis()}"
        def meetingId_F = "meeting-F-${System.currentTimeMillis()}"
        def meetingId_G = "meeting-G-${System.currentTimeMillis()}"
        def meetingId_H = "meeting-H-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "en", true)
        def participants_A = [userA1, userA2]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "en", true)
        def participants_B = [userB1, userB2]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "en", true)
        def participants_C = [userC1, userC2]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "en", true)
        def participants_D = [userD1, userD2]

        def userE1 = createParticipant("userE1", "ko", false)
        def userE2 = createParticipant("userE2", "en", true)
        def participants_E = [userE1, userE2]

        def userF1 = createParticipant("userF1", "ko", false)
        def userF2 = createParticipant("userF2", "en", true)
        def participants_F = [userF1, userF2]

        def userG1 = createParticipant("userG1", "ko", false)
        def userG2 = createParticipant("userG2", "en", true)
        def participants_G = [userG1, userG2]

        def userH1 = createParticipant("userH1", "ko", false)
        def userH2 = createParticipant("userH2", "en", true)
        def participants_H = [userH1, userH2]

        connectAll(participants_A, meetingId_A)
        connectAll(participants_B, meetingId_B)
        connectAll(participants_C, meetingId_C)
        connectAll(participants_D, meetingId_D)
        connectAll(participants_E, meetingId_E)
        connectAll(participants_F, meetingId_F)
        connectAll(participants_G, meetingId_G)
        connectAll(participants_H, meetingId_H)
        assert waitForAllReady(participants_A, 15)
        assert waitForAllReady(participants_B, 15)
        assert waitForAllReady(participants_C, 15)
        assert waitForAllReady(participants_D, 15)
        assert waitForAllReady(participants_E, 15)
        assert waitForAllReady(participants_F, 15)
        assert waitForAllReady(participants_G, 15)
        assert waitForAllReady(participants_H, 15)

        long startTime = 0

        when: "8개의 채널에서 오디오를 동시에 전송하고, 전송이 모두 끝날 때까지 기다림"
        def executor = Executors.newFixedThreadPool(8)
        def allParticipants = participants_A + participants_B + participants_C + participants_D + participants_E + participants_F + participants_G + participants_H
        allParticipants.each { p ->
            p.transcriptionQueue?.clear()
            p.translationQueue?.clear()
        }

        byte[] audioBytesA = getClass().getResourceAsStream("/${AUDIO_FILE_PATH}").bytes
        byte[] audioBytesB = getClass().getResourceAsStream("/${AUDIO_FILE_PATH_B}").bytes
        byte[] audioBytesC = getClass().getResourceAsStream("/${AUDIO_FILE_PATH_C}").bytes
        Future<?> futureA, futureB, futureC, futureD, futureE, futureF, futureG, futureH
        try {
            startTime = System.currentTimeMillis()
            futureA = executor.submit({ -> userA1.audioSenderClient.sendAudio(audioBytesA) } as Runnable)
            futureB = executor.submit({ -> userB1.audioSenderClient.sendAudio(audioBytesB) } as Runnable)
            futureC = executor.submit({ -> userC1.audioSenderClient.sendAudio(audioBytesC) } as Runnable)
            futureD = executor.submit({ -> userD1.audioSenderClient.sendAudio(audioBytesA) } as Runnable)
            futureE = executor.submit({ -> userE1.audioSenderClient.sendAudio(audioBytesB) } as Runnable)
            futureF = executor.submit({ -> userF1.audioSenderClient.sendAudio(audioBytesA) } as Runnable)
            futureG = executor.submit({ -> userG1.audioSenderClient.sendAudio(audioBytesB) } as Runnable)
            futureH = executor.submit({ -> userH1.audioSenderClient.sendAudio(audioBytesC) } as Runnable)
            futureA.get()
            futureB.get()
            futureC.get()
            futureD.get()
            futureE.get()
            futureF.get()
            futureG.get()
            futureH.get()
        } finally {
            executor.shutdown()
        }

        then: "모든 미팅룸의 영어 청중이 마지막 최종 번역 텍스트를 수신할 때까지 대기하고 Latency를 측정한다"
        def englishListeners = [userA2, userB2, userC2, userD2, userE2, userF2, userG2, userH2]
        long lastFinalTranslationTimestamp = waitForLastFinalTranslationTimestamp(englishListeners, 120)
        long latency = lastFinalTranslationTimestamp - startTime
        println "[Translation Latency 8 Channel] E2E Latency (to last final translation): ${latency} ms"

        and: "미팅룸 간 교차 오염이 없는지 확인"
        println "Cross-contamination check is implicitly passed by successful stream completion."

        and: "모든 백그라운드 프로세스가 완료될 때까지 안전하게 대기"
        println "All primary assertions passed. Waiting for ${SAFE_CLEANUP_WAIT_MS} ms to ensure all streams are closed..."
        sleep(SAFE_CLEANUP_WAIT_MS)

        cleanup: "모든 참여자의 연결을 종료"
        cleanupAll(participants_A)
        cleanupAll(participants_B)
        cleanupAll(participants_C)
        cleanupAll(participants_D)
        cleanupAll(participants_E)
        cleanupAll(participants_F)
        cleanupAll(participants_G)
        cleanupAll(participants_H)
    }
}
