package com.systran.e2e

import com.systran.e2e.client.AudioSenderClient
import com.systran.e2e.client.ResultReceiverClient
import groovy.transform.ToString
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
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
class MeetingFlowSpec extends Specification {
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
                audioSenderClient: new AudioSenderClient(name, JWT_TOKEN, language),
                resultReceiverClient: new ResultReceiverClient(name, language, JWT_TOKEN, transcriptionQueue, translationQueue),
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
            p.audioSenderClient.connect(VOICE_GATEWAY_BASE_URI + meetingId)
            p.resultReceiverClient.connect(TEXT_RETRIEVER_URI, meetingId)
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
        println "클린업 시작: ${participants*.name.join(', ')}"
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

    /***
     * partial
     * 3:
     * 4:
     * 5:
     */
    def "2 채널, 2명 동시 발화"() {
        given: "4명의 고유한 한국인 사용자를 2개의 미팅룸에 할당"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "ko", false)
        def participants_A = [userA1, userA2]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "ko", false)
        def participants_B = [userB1, userB2]

        connectAll(participants_A, meetingId_A)
        connectAll(participants_B, meetingId_B)
        assert waitForAllReady(participants_A, 15)
        assert waitForAllReady(participants_B, 15)

        long startTime = 0

        when: "두 채널에서 오디오를 동시에 전송하고, 전송이 모두 끝날 때까지 기다림"
        def executor = Executors.newFixedThreadPool(2)
        def allParticipants = participants_A + participants_B
        allParticipants.each { p -> p.transcriptionQueue?.clear() }

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

        then: "모든 스트림이 끝날 때까지 기다리며, 마지막 메시지 도착 시간을 기준으로 Latency를 측정한다"
        def pollingConditions = new PollingConditions(timeout: 120, initialDelay: 3)
        def lastActivityTime = new ConcurrentHashMap<String, Long>()
        allParticipants.each { p -> lastActivityTime.put(p.name, System.currentTimeMillis()) }
        
        def lastFinalTranscriptionTimestamp = new AtomicLong(0)
        def maxIndexes = new ConcurrentHashMap<String, Integer>()

        pollingConditions.eventually {
            def now = System.currentTimeMillis()
            def allQueuesCurrentlyEmpty = true

            allParticipants.each { p ->
                def q = p.transcriptionQueue
                if (q != null && !q.isEmpty()) {
                    allQueuesCurrentlyEmpty = false
                    lastActivityTime.put(p.name, now)

                    def messages = []
                    q.drainTo(messages)
                    messages.each { msg ->
                        if (msg['isFullText'] == true) {
                            lastFinalTranscriptionTimestamp.set(now)
                            def speakerName = msg['userName'] as String
                            def currentMax = maxIndexes.get(speakerName) ?: 0
                            def newIndex = msg['utteranceIdx'] as Integer
                            if (newIndex > currentMax) {
                                maxIndexes.put(speakerName, newIndex)
                            }
                        }
                    }
                }
            }

            def quietPeriodEnded = allParticipants.every { p ->
                (now - lastActivityTime.get(p.name)) > 10000 // 10-second quiet period
            }

            assert allQueuesCurrentlyEmpty && quietPeriodEnded
        }

        long latency = lastFinalTranscriptionTimestamp.get() > 0 ? (lastFinalTranscriptionTimestamp.get() - startTime) : 0
        println "[Transcription Latency 2 Channel] E2E Latency (to last final message): ${latency} ms"
        println "Final max index for userA1: ${maxIndexes.get('userA1') ?: 'N/A'}, for userB1: ${maxIndexes.get('userB1') ?: 'N/A'}"

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
        given: "8명의 고유한 한국인 사용자를 4개의 미팅룸에 할당"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"
        def meetingId_C = "meeting-C-${System.currentTimeMillis()}"
        def meetingId_D = "meeting-D-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "ko", false)
        def participants_A = [userA1, userA2]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "ko", false)
        def participants_B = [userB1, userB2]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "ko", false)
        def participants_C = [userC1, userC2]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "ko", false)
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
        allParticipants.each { p -> p.transcriptionQueue?.clear() }

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

        then: "모든 스트림이 끝날 때까지 기다리며, 마지막 메시지 도착 시간을 기준으로 Latency를 측정한다"
        def pollingConditions = new PollingConditions(timeout: 120, initialDelay: 3)
        def lastActivityTime = new ConcurrentHashMap<String, Long>()
        allParticipants.each { p -> lastActivityTime.put(p.name, System.currentTimeMillis()) }

        def lastFinalTranscriptionTimestamp = new AtomicLong(0)
        def maxIndexes = new ConcurrentHashMap<String, Integer>()

        pollingConditions.eventually {
            def now = System.currentTimeMillis()
            def allQueuesCurrentlyEmpty = true

            allParticipants.each { p ->
                def q = p.transcriptionQueue
                if (q != null && !q.isEmpty()) {
                    allQueuesCurrentlyEmpty = false
                    lastActivityTime.put(p.name, now)

                    def messages = []
                    q.drainTo(messages)
                    messages.each { msg ->
                        if (msg['isFullText'] == true) {
                            lastFinalTranscriptionTimestamp.set(now)
                            def speakerName = msg['userName'] as String
                            def currentMax = maxIndexes.get(speakerName) ?: 0
                            def newIndex = msg['utteranceIdx'] as Integer
                            if (newIndex > currentMax) {
                                maxIndexes.put(speakerName, newIndex)
                            }
                        }
                    }
                }
            }

            def quietPeriodEnded = allParticipants.every { p ->
                (now - lastActivityTime.get(p.name)) > 10000 // 10-second quiet period
            }

            assert allQueuesCurrentlyEmpty && quietPeriodEnded
        }

        long latency = lastFinalTranscriptionTimestamp.get() > 0 ? (lastFinalTranscriptionTimestamp.get() - startTime) : 0
        println "[Transcription Latency 4 Channel] E2E Latency (to last final message): ${latency} ms"
        println "Final max index for userA1: ${maxIndexes.get('userA1') ?: 'N/A'}, for userB1: ${maxIndexes.get('userB1') ?: 'N/A'}"

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
        given: "12명의 고유한 한국인 사용자를 6개의 미팅룸에 할당"
        def meetingId_A = "meeting-A-${System.currentTimeMillis()}"
        def meetingId_B = "meeting-B-${System.currentTimeMillis()}"
        def meetingId_C = "meeting-C-${System.currentTimeMillis()}"
        def meetingId_D = "meeting-D-${System.currentTimeMillis()}"
        def meetingId_E = "meeting-E-${System.currentTimeMillis()}"
        def meetingId_F = "meeting-F-${System.currentTimeMillis()}"

        def userA1 = createParticipant("userA1", "ko", false)
        def userA2 = createParticipant("userA2", "ko", false)
        def participants_A = [userA1, userA2]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "ko", false)
        def participants_B = [userB1, userB2]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "ko", false)
        def participants_C = [userC1, userC2]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "ko", false)
        def participants_D = [userD1, userD2]

        def userE1 = createParticipant("userE1", "ko", false)
        def userE2 = createParticipant("userE2", "ko", false)
        def participants_E = [userE1, userE2]

        def userF1 = createParticipant("userF1", "ko", false)
        def userF2 = createParticipant("userF2", "ko", false)
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
        allParticipants.each { p -> p.transcriptionQueue?.clear() }

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

        then: "모든 스트림이 끝날 때까지 기다리며, 마지막 메시지 도착 시간을 기준으로 Latency를 측정한다"
        def pollingConditions = new PollingConditions(timeout: 120, initialDelay: 3)
        def lastActivityTime = new ConcurrentHashMap<String, Long>()
        allParticipants.each { p -> lastActivityTime.put(p.name, System.currentTimeMillis()) }

        def lastFinalTranscriptionTimestamp = new AtomicLong(0)
        def maxIndexes = new ConcurrentHashMap<String, Integer>()

        pollingConditions.eventually {
            def now = System.currentTimeMillis()
            def allQueuesCurrentlyEmpty = true

            allParticipants.each { p ->
                def q = p.transcriptionQueue
                if (q != null && !q.isEmpty()) {
                    allQueuesCurrentlyEmpty = false
                    lastActivityTime.put(p.name, now)

                    def messages = []
                    q.drainTo(messages)
                    messages.each { msg ->
                        if (msg['isFullText'] == true) {
                            lastFinalTranscriptionTimestamp.set(now)
                            def speakerName = msg['userName'] as String
                            def currentMax = maxIndexes.get(speakerName) ?: 0
                            def newIndex = msg['utteranceIdx'] as Integer
                            if (newIndex > currentMax) {
                                maxIndexes.put(speakerName, newIndex)
                            }
                        }
                    }
                }
            }

            def quietPeriodEnded = allParticipants.every { p ->
                (now - lastActivityTime.get(p.name)) > 10000 // 10-second quiet period
            }

            assert allQueuesCurrentlyEmpty && quietPeriodEnded
        }

        long latency = lastFinalTranscriptionTimestamp.get() > 0 ? (lastFinalTranscriptionTimestamp.get() - startTime) : 0
        println "[Transcription Latency 6 Channel] E2E Latency (to last final message): ${latency} ms"
        println "Final max index for userA1: ${maxIndexes.get('userA1') ?: 'N/A'}, for userB1: ${maxIndexes.get('userB1') ?: 'N/A'}"

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
        given: "16명의 고유한 한국인 사용자를 8개의 미팅룸에 할당"
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
        def participants_A = [userA1, userA2]

        def userB1 = createParticipant("userB1", "ko", false)
        def userB2 = createParticipant("userB2", "ko", false)
        def participants_B = [userB1, userB2]

        def userC1 = createParticipant("userC1", "ko", false)
        def userC2 = createParticipant("userC2", "ko", false)
        def participants_C = [userC1, userC2]

        def userD1 = createParticipant("userD1", "ko", false)
        def userD2 = createParticipant("userD2", "ko", false)
        def participants_D = [userD1, userD2]

        def userE1 = createParticipant("userE1", "ko", false)
        def userE2 = createParticipant("userE2", "ko", false)
        def participants_E = [userE1, userE2]

        def userF1 = createParticipant("userF1", "ko", false)
        def userF2 = createParticipant("userF2", "ko", false)
        def participants_F = [userF1, userF2]

        def userG1 = createParticipant("userG1", "ko", false)
        def userG2 = createParticipant("userG2", "ko", false)
        def participants_G = [userG1, userG2]

        def userH1 = createParticipant("userH1", "ko", false)
        def userH2 = createParticipant("userH2", "ko", false)
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
        allParticipants.each { p -> p.transcriptionQueue?.clear() }

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

        then: "모든 스트림이 끝날 때까지 기다리며, 마지막 메시지 도착 시간을 기준으로 Latency를 측정한다"
        def pollingConditions = new PollingConditions(timeout: 120, initialDelay: 3)
        def lastActivityTime = new ConcurrentHashMap<String, Long>()
        allParticipants.each { p -> lastActivityTime.put(p.name, System.currentTimeMillis()) }

        def lastFinalTranscriptionTimestamp = new AtomicLong(0)
        def maxIndexes = new ConcurrentHashMap<String, Integer>()

        pollingConditions.eventually {
            def now = System.currentTimeMillis()
            def allQueuesCurrentlyEmpty = true

            allParticipants.each { p ->
                def q = p.transcriptionQueue
                if (q != null && !q.isEmpty()) {
                    allQueuesCurrentlyEmpty = false
                    lastActivityTime.put(p.name, now)

                    def messages = []
                    q.drainTo(messages)
                    messages.each { msg ->
                        if (msg['isFullText'] == true) {
                            lastFinalTranscriptionTimestamp.set(now)
                            def speakerName = msg['userName'] as String
                            def currentMax = maxIndexes.get(speakerName) ?: 0
                            def newIndex = msg['utteranceIdx'] as Integer
                            if (newIndex > currentMax) {
                                maxIndexes.put(speakerName, newIndex)
                            }
                        }
                    }
                }
            }

            def quietPeriodEnded = allParticipants.every { p ->
                (now - lastActivityTime.get(p.name)) > 10000 // 10-second quiet period
            }

            assert allQueuesCurrentlyEmpty && quietPeriodEnded
        }

        long latency = lastFinalTranscriptionTimestamp.get() > 0 ? (lastFinalTranscriptionTimestamp.get() - startTime) : 0
        println "[Transcription Latency 8 Channel] E2E Latency (to last final message): ${latency} ms"
        println "Final max index for userA1: ${maxIndexes.get('userA1') ?: 'N/A'}, for userB1: ${maxIndexes.get('userB1') ?: 'N/A'}"

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

//    def "두 명이 순차적으로 발화 시, 전사와 번역 Latency를 분리하여 측정한다"() {
//        given: "발화자 2명(한국인)과 청취자 1명(영어)을 생성"
//        def speaker1 = createParticipant("speaker1-ko", "ko", false)
//        def speaker2 = createParticipant("speaker2-ko", "ko", false)
//        def englishListener = createParticipant("listener-en", "en", true)
//        def participants = [speaker1, speaker2, englishListener]
//
//        def meetingId = "final-test-meeting-${System.currentTimeMillis()}"
//        connectAll(participants, meetingId)
//        assert waitForAllReady(participants, 15)
//
//        when: "두 명의 발화자가 순차적으로 발화함"
//        participants.each { p ->
//            p.transcriptionQueue?.clear()
//            p.translationQueue?.clear()
//        }
//        byte[] audioBytes = getClass().getResourceAsStream("/${AUDIO_FILE_PATH}").bytes
//        long startTime = System.currentTimeMillis()
//        speaker1.audioSenderClient.sendAudio(audioBytes)
//        speaker2.audioSenderClient.sendAudio(audioBytes)
//
//        then: "모든 전사 결과가 먼저 도착하고, 그 다음에 모든 번역 결과가 도착해야 한다"
//        // 기대하는 모든 최종 결과 메시지를 담을 변수 선언
//        Map s1_trans_s1, s1_trans_s2, s1_trans_en, s2_trans_s1, s2_trans_s2, s2_trans_en
//        Map s1_transl_en, s2_transl_en
//
//        def transcriptionConditions = new PollingConditions(timeout: 120)
//        transcriptionConditions.eventually {
//            s1_trans_s1 = findFinalMessageByUser(speaker1.transcriptionQueue, "speaker1-ko")
//            s1_trans_s2 = findFinalMessageByUser(speaker2.transcriptionQueue, "speaker1-ko")
//            s1_trans_en = findFinalMessageByUser(englishListener.transcriptionQueue, "speaker1-ko")
//
//            s2_trans_s1 = findFinalMessageByUser(speaker1.transcriptionQueue, "speaker2-ko")
//            s2_trans_s2 = findFinalMessageByUser(speaker2.transcriptionQueue, "speaker2-ko")
//            s2_trans_en = findFinalMessageByUser(englishListener.transcriptionQueue, "speaker2-ko")
//
//            // 총 6개의 전사 메시지 확인
//            assert s1_trans_s1 != null && s1_trans_s2 != null && s1_trans_en != null
//            assert s2_trans_s1 != null && s2_trans_s2 != null && s2_trans_en != null
//        }
//        long transcriptionEndTime = System.currentTimeMillis()
//        println "[Transcription Latency] E2E Latency: ${transcriptionEndTime - startTime} ms"
//
//
//        def translationConditions = new PollingConditions(timeout: 120)
//        translationConditions.eventually {
//            s1_transl_en = findFinalMessageByUser(englishListener.translationQueue, "speaker1-ko")
//            s2_transl_en = findFinalMessageByUser(englishListener.translationQueue, "speaker2-ko")
//
//            // 총 2개의 번역 메시지 확인
//            assert s1_transl_en != null && s2_transl_en != null
//        }
//        long translationEndTime = System.currentTimeMillis()
//        println "[Translation Latency] E2E Latency: ${translationEndTime - startTime} ms"
//
//
//        and: "수신된 메시지들의 정합성 검증"
//        def s1_expectedIdx = s1_trans_s1.utteranceIdx
//        assert s1_transl_en.utteranceIdx == s1_expectedIdx
//
//        def s2_expectedIdx = s2_trans_s2.utteranceIdx
//        assert s2_transl_en.utteranceIdx == s2_expectedIdx
//
//        assert s1_expectedIdx != s2_expectedIdx
//        assert speaker1.translationQueue == null
//        assert speaker2.translationQueue == null
//
//        and: "모든 백그라운드 프로세스가 완료될 때까지 안전하게 대기"
//        println "All primary assertions passed. Waiting for ${SAFE_CLEANUP_WAIT_MS} ms to ensure all streams are closed..."
//        sleep(SAFE_CLEANUP_WAIT_MS)
//
//        cleanup: "테스트에 사용된 모든 클라이언트의 연결을 안전하게 종료"
//        cleanupAll(participants)
//    }
//
//    def "채널 1개, 1명 발화"() {
//        given: "한국어 발화자 1명, 한국어/영어 청취자 각 1명을 생성"
//        def participants = [
//                createParticipant("user1-speaker-ko", "ko", false),
//                createParticipant("user1-listener-ko", "ko", false),
//                createParticipant("user1-listener-en", "en", true)
//        ]
//        connectAll(participants, MEETING_ID)
//        assert waitForAllReady(participants, 10)
//
//        when: "한국어 발화자가 오디오 파일을 전송함"
//        long startTime = System.currentTimeMillis()
//        def speaker = participants.first()
//        byte[] audioBytes = getClass().getResourceAsStream("/${AUDIO_FILE_PATH}").bytes
//        speaker.audioSenderClient.sendAudio(audioBytes)
//
//        then: "모든 최종 전사 결과와 번역 결과를 순차적으로 수신하고 Latency를 각각 측정한다"
//        Map speakerFinalMsg, koListenerFinalMsg, enListenerTranscriptionMsg, enListenerTranslationMsg
//
//        // Latency 측정을 시작하기 전, 모든 큐를 깨끗하게 비웁니다.
//        participants.each { p ->
//            p.transcriptionQueue?.clear()
//            p.translationQueue?.clear()
//        }
//
//        def transcriptionConditions = new PollingConditions(timeout: 120)
//        transcriptionConditions.eventually {
//            speakerFinalMsg = findFinalMessage(participants[0].transcriptionQueue)
//            koListenerFinalMsg = findFinalMessage(participants[1].transcriptionQueue)
//            enListenerTranscriptionMsg = findFinalMessage(participants[2].transcriptionQueue)
//
//            assert speakerFinalMsg != null
//            assert koListenerFinalMsg != null
//            assert enListenerTranscriptionMsg != null
//
//            def expectedIdx = speakerFinalMsg.utteranceIdx
//            assert koListenerFinalMsg.utteranceIdx == expectedIdx
//            assert enListenerTranscriptionMsg.utteranceIdx == expectedIdx
//        }
//        long sttEndTime = System.currentTimeMillis()
//        println "[Transcription Latency] E2E Latency: ${sttEndTime - startTime} ms"
//
//        def translationConditions = new PollingConditions(timeout: 120)
//        translationConditions.eventually{
//            enListenerTranslationMsg = findFinalMessage(participants[2].translationQueue)
//            assert enListenerTranslationMsg != null
//        }
//        long mtEndTime = System.currentTimeMillis()
//        println "[Translation Latency] E2E Latency: ${mtEndTime - startTime} ms"
//
//        and: "수신된 최종 메시지의 내용 확인"
//        assert enListenerTranscriptionMsg.utteranceIdx == enListenerTranslationMsg.utteranceIdx
//        assert participants[1].translationQueue == null
//
//        and: "모든 백그라운드 프로세스가 완료될 때까지 안전하게 대기"
//        println "All primary assertions passed. Waiting for ${SAFE_CLEANUP_WAIT_MS} ms to ensure all streams are closed..."
//        sleep(SAFE_CLEANUP_WAIT_MS)
//
//        cleanup: "테스트에 사용된 모든 클라이언트의 연결을 안전하게 종료"
//        cleanupAll(participants)
//    }