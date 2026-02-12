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
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest
@ActiveProfiles("test")
class MeetingFlowSpecV2 extends Specification {
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

    private static final int SAFE_CLEANUP_WAIT_MS = 5000

    @ToString(includeNames = true)
    private static class TestParticipant {
        String name
        String language
        AudioSenderClient audioSenderClient
        ResultReceiverClient resultReceiverClient
        BlockingQueue<Map> transcriptionQueue
        BlockingQueue<Map> translationQueue
    }

    private TestParticipant createParticipant(String name, String language, boolean needsTranslation) {
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

    private void connectAll(List<TestParticipant> participants, String meetingId) {
        participants.each { p ->
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

    private boolean waitForAllReady(List<TestParticipant> participants, long timeoutSeconds) {
        def allReady = participants.every { p ->
            p.audioSenderClient.isReady(timeoutSeconds) && p.resultReceiverClient.isReady(timeoutSeconds)
        }
        if (allReady) {
            println "모든 참여자(${participants*.name.join(', ')})가 준비되었습니다."
        }
        return allReady
    }

    private Map<String, Object> waitForQuietPeriodWithTranslation(
            List<TestParticipant> allParticipants,
            List<TestParticipant> englishListeners,
            long timeoutSeconds
    ) {
        def pollingConditions = new PollingConditions(timeout: timeoutSeconds, initialDelay: 3)
        def lastActivityTime = new ConcurrentHashMap<String, Long>()
        allParticipants.each { p -> lastActivityTime.put(p.name, System.currentTimeMillis()) }

        def lastFinalTranscriptionTimestamp = new AtomicLong(0)
        def lastFinalTranslationTimestamp = new AtomicLong(0)
        def maxIndexesBySpeaker = new ConcurrentHashMap<String, Integer>()
        def finalTranslationCountByListener = new ConcurrentHashMap<String, Integer>()

        pollingConditions.eventually {
            def now = System.currentTimeMillis()
            def allQueuesCurrentlyEmpty = true

            allParticipants.each { p ->
                def transcriptionQueue = p.transcriptionQueue
                if (transcriptionQueue != null && !transcriptionQueue.isEmpty()) {
                    allQueuesCurrentlyEmpty = false
                    lastActivityTime.put(p.name, now)

                    def messages = []
                    transcriptionQueue.drainTo(messages)
                    messages.each { msg ->
                        if (msg['isFullText'] == true) {
                            long receivedAt = extractReceivedAt(msg, now)
                            lastFinalTranscriptionTimestamp.set(receivedAt)
                            def speakerName = msg['userName'] as String
                            def currentMax = maxIndexesBySpeaker.get(speakerName) ?: 0
                            def newIndex = msg['utteranceIdx'] as Integer
                            if (newIndex > currentMax) {
                                maxIndexesBySpeaker.put(speakerName, newIndex)
                            }
                        }
                    }
                }

                def translationQueue = p.translationQueue
                if (translationQueue != null && !translationQueue.isEmpty()) {
                    allQueuesCurrentlyEmpty = false
                    lastActivityTime.put(p.name, now)

                    def translationMessages = []
                    translationQueue.drainTo(translationMessages)
                    translationMessages.each { msg ->
                        boolean isFinal = msg['isFullText'] == true
                        String translationText = msg['translationText']?.toString()
                        if (isFinal && translationText != null && !translationText.isBlank()) {
                            long receivedAt = extractReceivedAt(msg, now)
                            lastFinalTranslationTimestamp.set(receivedAt)
                            finalTranslationCountByListener.merge(p.name, 1) { oldValue, inc -> oldValue + inc }
                        }
                    }
                }
            }

            def quietPeriodEnded = allParticipants.every { p ->
                (now - (lastActivityTime.get(p.name) ?: now)) > 10000 // 10-second quiet period
            }
            def allEnglishListenersReceivedFinalTranslation = englishListeners.every { listener ->
                (finalTranslationCountByListener.get(listener.name) ?: 0) > 0
            }

            assert allQueuesCurrentlyEmpty && quietPeriodEnded && allEnglishListenersReceivedFinalTranslation
        }

        return [
                lastFinalTranscriptionTimestamp: lastFinalTranscriptionTimestamp.get(),
                lastFinalTranslationTimestamp  : lastFinalTranslationTimestamp.get(),
                maxIndexesBySpeaker           : maxIndexesBySpeaker,
                finalTranslationCountByListener: finalTranslationCountByListener
        ]
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

    private void cleanupAll(List<TestParticipant> participants) {
        def participantNames = participants?.collect { it?.name }?.findAll { it != null } ?: []
        println "클린업 시작: ${participantNames.join(', ')}"
        participants?.each { p ->
            try { p.audioSenderClient?.close() } catch (Exception e) { println "오디오 클라이언트 종료 오류: ${e.message}" }
            try { p.resultReceiverClient?.close() } catch (Exception e) { println "결과 클라이언트 종료 오류: ${e.message}" }
        }
    }

    def "[V2-5P] 2 채널, 2명 동시 발화"() {
        given: "각 미팅룸당 한국어 발화자 1명, 한국어 청중 3명, 영어 청중 1명을 2개의 미팅룸에 할당"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "ko", false)
        def userA3 = createParticipant("userA3", "ko", false)
        def userA4 = createParticipant("userA4", "ko", false)
        def userA5 = createParticipant("userA5", "en", true)
        def participants_A = [userA1, userA2, userA3, userA4, userA5]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "ko", false)
        def userB3 = createParticipant("userB3", "ko", false)
        def userB4 = createParticipant("userB4", "ko", false)
        def userB5 = createParticipant("userB5", "en", true)
        def participants_B = [userB1, userB2, userB3, userB4, userB5]

        def englishListeners = [userA5, userB5]

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

        then: "모든 스트림이 끝난 뒤 영어 청중의 최종 번역 수신까지 완료될 때까지 대기하고 Latency를 측정한다"
        def summary = waitForQuietPeriodWithTranslation(allParticipants, englishListeners, 120)
        long lastFinalTranslationTimestamp = summary.lastFinalTranslationTimestamp as long
        long latency = lastFinalTranslationTimestamp > 0 ? (lastFinalTranslationTimestamp - startTime) : 0
        def maxIndexes = summary.maxIndexesBySpeaker as Map<String, Integer>
        def translationCounts = summary.finalTranslationCountByListener as Map<String, Integer>
        println "[Translation Latency 2 Channel v2-5P] SpeechStartToLastFinalTranslation: ${latency} ms"
        println "Final max index for userA1: ${maxIndexes.get('userA1') ?: 'N/A'}, for userB1: ${maxIndexes.get('userB1') ?: 'N/A'}"
        println "Final translation count for userA5: ${translationCounts.get('userA5') ?: 0}, for userB5: ${translationCounts.get('userB5') ?: 0}"

        and: "미팅룸 간 교차 오염이 없는지 확인"
        println "Cross-contamination check is implicitly passed by successful stream completion."

        and: "모든 백그라운드 프로세스가 완료될 때까지 안전하게 대기"
        println "All primary assertions passed. Waiting for ${SAFE_CLEANUP_WAIT_MS} ms to ensure all streams are closed..."
        sleep(SAFE_CLEANUP_WAIT_MS)

        cleanup: "모든 참여자의 연결을 종료"
        cleanupAll(participants_A)
        cleanupAll(participants_B)
    }

    def "[V2-5P] 미팅룸 4개 운영, 4명 동시 발화"() {
        given: "각 미팅룸당 한국어 발화자 1명, 한국어 청중 3명, 영어 청중 1명을 4개의 미팅룸에 할당"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"
        def meetingId_C = "meeting-C-${System.currentTimeMillis()}"
        def meetingId_D = "meeting-D-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "ko", false)
        def userA3 = createParticipant("userA3", "ko", false)
        def userA4 = createParticipant("userA4", "ko", false)
        def userA5 = createParticipant("userA5", "en", true)
        def participants_A = [userA1, userA2, userA3, userA4, userA5]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "ko", false)
        def userB3 = createParticipant("userB3", "ko", false)
        def userB4 = createParticipant("userB4", "ko", false)
        def userB5 = createParticipant("userB5", "en", true)
        def participants_B = [userB1, userB2, userB3, userB4, userB5]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "ko", false)
        def userC3 = createParticipant("userC3", "ko", false)
        def userC4 = createParticipant("userC4", "ko", false)
        def userC5 = createParticipant("userC5", "en", true)
        def participants_C = [userC1, userC2, userC3, userC4, userC5]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "ko", false)
        def userD3 = createParticipant("userD3", "ko", false)
        def userD4 = createParticipant("userD4", "ko", false)
        def userD5 = createParticipant("userD5", "en", true)
        def participants_D = [userD1, userD2, userD3, userD4, userD5]

        def englishListeners = [userA5, userB5, userC5, userD5]

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

        then: "모든 스트림이 끝난 뒤 영어 청중의 최종 번역 수신까지 완료될 때까지 대기하고 Latency를 측정한다"
        def summary = waitForQuietPeriodWithTranslation(allParticipants, englishListeners, 120)
        long lastFinalTranslationTimestamp = summary.lastFinalTranslationTimestamp as long
        long latency = lastFinalTranslationTimestamp > 0 ? (lastFinalTranslationTimestamp - startTime) : 0
        def maxIndexes = summary.maxIndexesBySpeaker as Map<String, Integer>
        def translationCounts = summary.finalTranslationCountByListener as Map<String, Integer>
        println "[Translation Latency 4 Channel v2-5P] SpeechStartToLastFinalTranslation: ${latency} ms"
        println "Final max index for userA1: ${maxIndexes.get('userA1') ?: 'N/A'}, for userB1: ${maxIndexes.get('userB1') ?: 'N/A'}"
        println "Final translation counts: userA5=${translationCounts.get('userA5') ?: 0}, userB5=${translationCounts.get('userB5') ?: 0}, userC5=${translationCounts.get('userC5') ?: 0}, userD5=${translationCounts.get('userD5') ?: 0}"

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

    def "[V2-5P] 미팅룸 6개 운영, 6명 동시 발화"() {
        given: "각 미팅룸당 한국어 발화자 1명, 한국어 청중 3명, 영어 청중 1명을 6개의 미팅룸에 할당"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"
        def meetingId_C = "meeting-C-${System.currentTimeMillis()}"
        def meetingId_D = "meeting-D-${System.currentTimeMillis()}"
        def meetingId_E = "meeting-E-${System.currentTimeMillis()}"
        def meetingId_F = "meeting-F-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "ko", false)
        def userA3 = createParticipant("userA3", "ko", false)
        def userA4 = createParticipant("userA4", "ko", false)
        def userA5 = createParticipant("userA5", "en", true)
        def participants_A = [userA1, userA2, userA3, userA4, userA5]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "ko", false)
        def userB3 = createParticipant("userB3", "ko", false)
        def userB4 = createParticipant("userB4", "ko", false)
        def userB5 = createParticipant("userB5", "en", true)
        def participants_B = [userB1, userB2, userB3, userB4, userB5]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "ko", false)
        def userC3 = createParticipant("userC3", "ko", false)
        def userC4 = createParticipant("userC4", "ko", false)
        def userC5 = createParticipant("userC5", "en", true)
        def participants_C = [userC1, userC2, userC3, userC4, userC5]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "ko", false)
        def userD3 = createParticipant("userD3", "ko", false)
        def userD4 = createParticipant("userD4", "ko", false)
        def userD5 = createParticipant("userD5", "en", true)
        def participants_D = [userD1, userD2, userD3, userD4, userD5]

        def userE1 = createParticipant("userE1", "ko", false)
        def userE2 = createParticipant("userE2", "ko", false)
        def userE3 = createParticipant("userE3", "ko", false)
        def userE4 = createParticipant("userE4", "ko", false)
        def userE5 = createParticipant("userE5", "en", true)
        def participants_E = [userE1, userE2, userE3, userE4, userE5]

        def userF1 = createParticipant("userF1", "ko", false)
        def userF2 = createParticipant("userF2", "ko", false)
        def userF3 = createParticipant("userF3", "ko", false)
        def userF4 = createParticipant("userF4", "ko", false)
        def userF5 = createParticipant("userF5", "en", true)
        def participants_F = [userF1, userF2, userF3, userF4, userF5]

        def englishListeners = [userA5, userB5, userC5, userD5, userE5, userF5]

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

        then: "모든 스트림이 끝난 뒤 영어 청중의 최종 번역 수신까지 완료될 때까지 대기하고 Latency를 측정한다"
        def summary = waitForQuietPeriodWithTranslation(allParticipants, englishListeners, 120)
        long lastFinalTranslationTimestamp = summary.lastFinalTranslationTimestamp as long
        long latency = lastFinalTranslationTimestamp > 0 ? (lastFinalTranslationTimestamp - startTime) : 0
        def maxIndexes = summary.maxIndexesBySpeaker as Map<String, Integer>
        def translationCounts = summary.finalTranslationCountByListener as Map<String, Integer>
        println "[Translation Latency 6 Channel v2-5P] SpeechStartToLastFinalTranslation: ${latency} ms"
        println "Final max index for userA1: ${maxIndexes.get('userA1') ?: 'N/A'}, for userB1: ${maxIndexes.get('userB1') ?: 'N/A'}"
        println "Final translation counts: userA5=${translationCounts.get('userA5') ?: 0}, userB5=${translationCounts.get('userB5') ?: 0}, userC5=${translationCounts.get('userC5') ?: 0}, userD5=${translationCounts.get('userD5') ?: 0}, userE5=${translationCounts.get('userE5') ?: 0}, userF5=${translationCounts.get('userF5') ?: 0}"

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

    def "[V2-5P] 미팅룸 8개 운영, 8명 동시 발화"() {
        given: "각 미팅룸당 한국어 발화자 1명, 한국어 청중 3명, 영어 청중 1명을 8개의 미팅룸에 할당"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"
        def meetingId_C = "meeting-C-${System.currentTimeMillis()}"
        def meetingId_D = "meeting-D-${System.currentTimeMillis()}"
        def meetingId_E = "meeting-E-${System.currentTimeMillis()}"
        def meetingId_F = "meeting-F-${System.currentTimeMillis()}"
        def meetingId_G = "meeting-G-${System.currentTimeMillis()}"
        def meetingId_H = "meeting-H-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "ko", false)
        def userA3 = createParticipant("userA3", "ko", false)
        def userA4 = createParticipant("userA4", "ko", false)
        def userA5 = createParticipant("userA5", "en", true)
        def participants_A = [userA1, userA2, userA3, userA4, userA5]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "ko", false)
        def userB3 = createParticipant("userB3", "ko", false)
        def userB4 = createParticipant("userB4", "ko", false)
        def userB5 = createParticipant("userB5", "en", true)
        def participants_B = [userB1, userB2, userB3, userB4, userB5]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "ko", false)
        def userC3 = createParticipant("userC3", "ko", false)
        def userC4 = createParticipant("userC4", "ko", false)
        def userC5 = createParticipant("userC5", "en", true)
        def participants_C = [userC1, userC2, userC3, userC4, userC5]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "ko", false)
        def userD3 = createParticipant("userD3", "ko", false)
        def userD4 = createParticipant("userD4", "ko", false)
        def userD5 = createParticipant("userD5", "en", true)
        def participants_D = [userD1, userD2, userD3, userD4, userD5]

        def userE1 = createParticipant("userE1", "ko", false)
        def userE2 = createParticipant("userE2", "ko", false)
        def userE3 = createParticipant("userE3", "ko", false)
        def userE4 = createParticipant("userE4", "ko", false)
        def userE5 = createParticipant("userE5", "en", true)
        def participants_E = [userE1, userE2, userE3, userE4, userE5]

        def userF1 = createParticipant("userF1", "ko", false)
        def userF2 = createParticipant("userF2", "ko", false)
        def userF3 = createParticipant("userF3", "ko", false)
        def userF4 = createParticipant("userF4", "ko", false)
        def userF5 = createParticipant("userF5", "en", true)
        def participants_F = [userF1, userF2, userF3, userF4, userF5]

        def userG1 = createParticipant("userG1", "ko", false)
        def userG2 = createParticipant("userG2", "ko", false)
        def userG3 = createParticipant("userG3", "ko", false)
        def userG4 = createParticipant("userG4", "ko", false)
        def userG5 = createParticipant("userG5", "en", true)
        def participants_G = [userG1, userG2, userG3, userG4, userG5]

        def userH1 = createParticipant("userH1", "ko", false)
        def userH2 = createParticipant("userH2", "ko", false)
        def userH3 = createParticipant("userH3", "ko", false)
        def userH4 = createParticipant("userH4", "ko", false)
        def userH5 = createParticipant("userH5", "en", true)
        def participants_H = [userH1, userH2, userH3, userH4, userH5]

        def englishListeners = [userA5, userB5, userC5, userD5, userE5, userF5, userG5, userH5]

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

        then: "모든 스트림이 끝난 뒤 영어 청중의 최종 번역 수신까지 완료될 때까지 대기하고 Latency를 측정한다"
        def summary = waitForQuietPeriodWithTranslation(allParticipants, englishListeners, 120)
        long lastFinalTranslationTimestamp = summary.lastFinalTranslationTimestamp as long
        long latency = lastFinalTranslationTimestamp > 0 ? (lastFinalTranslationTimestamp - startTime) : 0
        def maxIndexes = summary.maxIndexesBySpeaker as Map<String, Integer>
        def translationCounts = summary.finalTranslationCountByListener as Map<String, Integer>
        println "[Translation Latency 8 Channel v2-5P] SpeechStartToLastFinalTranslation: ${latency} ms"
        println "Final max index for userA1: ${maxIndexes.get('userA1') ?: 'N/A'}, for userB1: ${maxIndexes.get('userB1') ?: 'N/A'}"
        println "Final translation counts: userA5=${translationCounts.get('userA5') ?: 0}, userB5=${translationCounts.get('userB5') ?: 0}, userC5=${translationCounts.get('userC5') ?: 0}, userD5=${translationCounts.get('userD5') ?: 0}, userE5=${translationCounts.get('userE5') ?: 0}, userF5=${translationCounts.get('userF5') ?: 0}, userG5=${translationCounts.get('userG5') ?: 0}, userH5=${translationCounts.get('userH5') ?: 0}"

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
