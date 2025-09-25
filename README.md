## 1. Projects

- **Backend (Spring Boot 3.5.5 & Java 21)**
  - `voice-gateway`
  - `translation-bridge`
  - `text-retreiver`
- **Frontend (React & TypeScript)**
  - `business-meeting-fronted-main`
- **LoadTest (Spring Boot 3.5.5 & Java 21)**
  - `e2e-test`

---

## 2. Test Environment

본 부하 테스트는 아래와 같이 클라이언트와 서버 환경을 분리하여 진행되었습니다.

### 테스트 대상 서버 (System Under Test)

애플리케이션이 배포되어 부하를 직접 받는 서버의 사양입니다.

| Component  | Specification                                           |
|------------|---------------------------------------------------------|
| **CPU**    | Intel(R) Core(TM) i5-9400F @ 2.90GHz (6 Cores / 6 Threads) |
| **GPU**    | NVIDIA Quadro P5000                                     |
| **Memory** | 32GB                                                    |
| **OS**     | Ubuntu 22.04.3 LTS                                      |

### 부하 발생 클라이언트 (Load Generator Client)

부하 테스트 스크립트(`e2e-test`)를 실행하여 서버에 요청을 보낸 클라이언트 PC의 사양입니다.

| Component | Specification |
| --- | --- |
| **CPU** | Apple M1 |
| **Memory** | 16GB |
| **Tool** | Jemeter |

### Audio File

e2e-test 서버에서 Voice 서버로 보내는 오디오 파일 사양입니다.

| Component | Specification |
| --- | --- |
| **Sampling Rate** | 16kHz |
| **Audio Channel** | MONO |

---

## 3. Load Test Results

### Common Config
- Concurrency: Meeting Room Count (동시 발화자 수)
  > EX: Concurrency = 2
  > 
  > 각 Meeting Room(2개)에서 발화자 1명 오디오 파일 전송, 즉 Meeting Room 2개만큼 오디오 파일 동시 전송 
- user_count: Meeting Room 1개에 참가하고 있는 유저의 수
  > EX: user_count = 10, Concurrency = 1
  > 
  > Meeting Room에 참여자 수는 10명이며, 이 중 1명은 오디오를 전송하고 발화자 포함 10명은 텍스트를 수신한다.
- Audio File Length: 60s

---

### Auth Gide
초기 버전에서는 DB Server에 직접 Rest API 통신을 통해, Access Token을 발급했지만
동시 요청 수가 많아지면서 부하 테스트 목적과 상관없는 Query 지연 시간이 발생하여 
Access Token을 직접 발급받아 YML 파일에 하드코딩하는 방식으로 테스트 진행

1. http://192.168.0.27:8889/docs 에서 /token API를 통해 토큰 발급
 - username: user123@example.com
 - password: aA!23456
2. `application.yml` & `application-test.yml`에 `test.jwt.token` 항목에 발급 받은 토큰 기입

---

### Result with Real STT

**실행 방법**
`test/groovy/com/systran/e2e/MeetingFlowSpec`에 작성된 단위 테스트 RUN  

- **Latency(s)(RTF)**
- EX: 74.8(0.80) → Latency = 74.8s, RTF = 0.80
- **user_count: 2**

| | Concurrency: 2 | Concurrency: 4 | Concurrency: 6 | Concurrency: 8 |
| :--- |:---------------|:---------------|:---------------|:---------------|
| **partial: 3** | 74.8(0.80)           | 105.9(0.56)          | 161.8(0.37)          | fail           |
| **partial: 4** | 74.8(0.80)           | 87.2(0.68)           | 126.1(0.47)          | 170.9(0.35)    |
| **partial: 5** | 74.8(0.80)           | 74.5(0.80)           | 87.4(0.68)           | 118.8(0.50)    |

> Real-Time Factor (RTF): 음성 처리 시스템 성능 지표
>
>  [원본 오디오 파일 길이(s) / 전사 지연 시간(s)]

---

### Result with Dummy STT

**실행 방법**
1. `load_test/anchor_load_test.jmx` 스크립트를 `Jemeter`로 실행
2. `Jemeter`의 `users`는 `Concurrency` 설정임
3. 요청 Body에 `listenerCount`는 `user_count` 설정임

- **Latency(s)**

| | Concurrency: 10 | Concurrency: 13 | Concurrency: 15 | Concurrency: 18 |
| :--- |:----------------|:----------------|:----------------| :--- |
| **user_count: 10** | 73.7            | 78.0            | 79.1            | fail |

> CPU Usage: Avg 15~20% (Peak 30%)

---

### Result without STT

**실행 방법**
1. `load_test/anchor_load_test.jmx` 스크립트를 `Jemeter`로 실행
2. `Jemeter`의 `users`는 `Concurrency` 설정임
3. 요청 Body에 `listenerCount`는 `user_count` 설정임

- **Latency(s)**

| | Concurrency: 30 | Concurrency: 35 | Concurrency: 40 | Concurrency: 45 |
| :--- |:----------------|:----------------|:----------------| :--- |
| **user_count: 10** | 64.2            | 64.7            | 66.2            | fail |

> CPU Usage: Avg 20~25% (Peak 36%)