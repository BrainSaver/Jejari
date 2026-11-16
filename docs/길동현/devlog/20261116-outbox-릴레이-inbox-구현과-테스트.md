# 2026-11-16 · Outbox 기록·릴레이·Inbox 구현과 테스트 (EVT-01~03)

> 작성: 길동현 · 관련: EVT-01, EVT-02, EVT-03, concurrency.md §11·§12, 커밋 `0f9f181`
> 파일 이름: `docs/길동현/devlog/20261116-outbox-릴레이-inbox-구현과-테스트.md`
> 오전 작업(역할 정리·인프라)은 `20261116-역할-정리-미정사항-확정-로컬-인프라.md`

## 한 일

- 전체 흐름(예약 → 결제 → Outbox → Kafka → 소비자)을 애니메이션 HTML로 정리해 흐름 파악용으로 사용
- `com.jejari.event` 패키지 신설
  - 이벤트 형식: `EventType`, `AggregateType`(토픽 매핑), `EventTopics`(토픽·컨슈머 그룹·헤더 이름), `EventEnvelope`(JSON 봉투)
  - **EVT-01 Outbox 기록** `OutboxRecorder`: 업무 트랜잭션 안에서 `outbox_event` INSERT, 샤드는 `ORA_HASH(TO_CHAR(?), 15)`
  - **EVT-02 릴레이** `OutboxRelay` + `OutboxRelayScheduler`(200ms 주기) + `KafkaEventPublisher`
  - **EVT-03 Inbox** `InboxGuard`·`InboxProcessor`: `processed_event` INSERT와 업무 처리를 한 트랜잭션으로
  - 정책 읽기 `EventPolicy`(app_policy, 10초 캐시, 실패 시 기본값), 정리 배치 `EventCleanup`(매일 04:10)
- `application.yml`에 `jejari.event.*` 설정 추가 (릴레이·정리 배치 on/off, 주기, 캐시)
- 실제 Oracle·Kafka로 처음부터 끝까지 확인: 테스트 행 INSERT → 릴레이가 PUBLISHED로 바꿈 → Kafka 토픽에서 수신 → 행 삭제
- 테스트 27개 작성, 모두 통과

  | 테스트 | 개수 | 필요 환경 |
  |---|---|---|
  | `EventEnvelopeTest` | 2 | 없음 |
  | `EventPolicyTest` | 5 | 없음 (Mockito) |
  | `OutboxInboxInfraTest` | 9 | Oracle |
  | `OutboxCommitInfraTest` | 10 | Oracle (실제 커밋) |
  | `KafkaEventPublisherInfraTest` | 1 | Kafka |

## 어떻게 했나

- 흐름: `업무 TX { 업무 UPDATE + outbox INSERT } 커밋 → 릴레이가 샤드별로 맨 앞 N개 잠금 → seq 순서로 Kafka 발행 → PUBLISHED 표시 → 소비자 TX { processed_event INSERT + 처리 }`
- 다른 팀원이 부를 방법 (예: 강동균 좌석 선점)

  ```java
  @Transactional
  public void hold(...) {
      // 업무 처리 …
      outbox.recordReservation(reservationId, EventType.SEAT_HELD, payload);
  }
  ```

  `@Transactional(propagation = MANDATORY)`라서 트랜잭션 밖에서 부르면 바로 예외가 난다.
- 릴레이 잠금 SQL (§11.3): 안쪽 쿼리로 맨 앞 N개의 rowid만 뽑고 그 행만 `FOR UPDATE WAIT 5`
- 실패 처리: 발행 실패 → `retry_count + 1`, 그 샤드 이번 회차 중단. 5회 실패 → `FAILED`, 그 샤드는 관리자가 `PENDING`·0으로 되돌릴 때까지 정지 (다른 샤드는 계속)
- Inbox: `(consumer_group, event_id)` PK에 INSERT. `DuplicateKeyException`이면 이미 처리한 이벤트라 건너뜀
- 테스트 실행

  ```powershell
  .\gradlew test --tests "com.jejari.event.*"        # 인프라 불필요
  .\gradlew infraTest --tests "com.jejari.event.*"   # Docker 필요, 앱(bootRun)은 끄고
  ```

## 왜 이렇게 했나

- **Outbox를 쓰는 이유**: DB 커밋과 Kafka 발행을 따로 하면 "DB는 커밋됐는데 발행 전에 죽음"(이벤트 유실)이나 "발행했는데 DB는 롤백"(유령 이벤트)이 생긴다. 이벤트를 같은 트랜잭션에 행으로 남기면 둘이 항상 같이 성공하거나 같이 실패한다.
- **SKIP LOCKED 대신 WAIT**: 잠긴 행을 건너뛰면 같은 예약의 다음 이벤트가 먼저 나가 순서가 깨진다. 기다렸다가 이번 회차를 포기하는 쪽을 골랐다.
- **실패하면 샤드 회차 중단**: 뒤 행은 같은 예약의 다음 이벤트일 수 있다. 결제 확정이 좌석 선점보다 먼저 도착하면 안 된다.
- **릴레이는 최소 한 번(at-least-once)**: 보낸 뒤 PUBLISHED 표시 전에 죽으면 다시 보낸다. 그래서 중복 제거는 소비자 쪽 Inbox가 맡는다. "중복 0건"은 릴레이 + Inbox 둘이 합쳐서 보장한다.
- **1차는 인스턴스 1대가 16개 샤드를 모두 처리**: Redis 리스(§11.2)는 인스턴스를 늘릴 때 `relayShard` 앞에 붙이면 된다.
- **정책은 app_policy에서 읽음**: 배치 크기·재시도 횟수를 재배포 없이 바꿀 수 있다. DB를 못 읽어도 기본값으로 계속 돈다.
- **감수한 단점**: 샤드 하나가 FAILED면 그 샤드에 속한 다른 예약 이벤트도 같이 멈춘다 (순서 보장을 위해 감수).

## 막힌 점과 해결

- **IntelliJ에서 테스트 1개 통과, 8개 실패**: Oracle 컨테이너가 재시작된 직후라 FREEPDB1이 아직 열리지 않았다 (ORA-01109, ORA-12514). 컨테이너가 `healthy`여도 PDB는 몇 분 뒤에 열린다. `docker logs jejari-oracle-1 2>&1 | Select-String "FREEPDB1 opened"`를 확인하고 다시 돌리니 9/9 통과.
- **처음 테스트가 트랜잭션 경계를 검증하지 못함**: `@JdbcTest`는 테스트 하나를 트랜잭션 하나로 감싸고 끝에 롤백한다. 그래서 커밋·롤백, 다른 연결에서 보이는지, 동시 실행을 실제로는 확인하지 못했다. 실제로 커밋하고 끝에 직접 지우는 `OutboxCommitInfraTest`(10개)를 추가했다.
  - 업무 롤백 시 outbox 행도 사라짐 / 커밋 전에는 릴레이가 못 봄
  - 릴레이 2개 동시 실행으로 40건이 한 번씩, 순서대로 나감
  - 잠금 대기 5초 후 이번 회차 포기
  - 보낸 뒤 표시 전에 끊기면 같은 event_id로 재발송되고 Inbox가 한 번만 처리
  - 한 샤드 정지가 다른 샤드에 번지지 않음, relay_batch 지킴, 정리 배치는 기한 지난 행만 삭제
- **테스트가 버그를 정말 잡는지 확인 (뮤테이션 테스트)**: 릴레이에 일부러 버그를 넣고 돌림
  - 발행 실패 후에도 계속 보냄 → 테스트 3개 실패 (잡음)
  - 맨 앞이 FAILED여도 계속 처리 → 테스트 2개 실패 (잡음)
  - 바깥 `status IN ('PENDING','FAILED')` 조건 삭제 → 못 잡음. Oracle은 잠금 대기 중 행이 바뀌면 쿼리를 다시 실행하고, 안쪽 쿼리에 같은 조건이 있어 결과가 같다. 바깥 조건은 §11.3의 안전장치로 남겨 둠 (동작을 보고 추론한 것이라 Oracle 내부는 직접 확인하지 않음)
  - 버그를 넣었던 코드는 원래대로 되돌리고 27개 다시 통과 확인

## 배운 점

- 트랜잭션을 롤백하는 테스트는 "트랜잭션 경계"를 검증하지 못한다. Outbox처럼 커밋 시점이 핵심인 기능은 실제로 커밋하는 테스트가 따로 있어야 한다.
- 테스트가 통과하는 것만 보지 말고 일부러 코드를 망가뜨려 테스트가 실패하는지 확인해야 그 테스트를 믿을 수 있다.
- Oracle 컨테이너의 `healthy`는 "DB를 쓸 수 있음"과 다르다. 재시작 직후엔 PDB가 열렸는지 로그로 확인한다.
- 실제로 커밋하는 테스트는 실행 중인 앱의 릴레이와 행을 두고 경쟁한다. 테스트 전에 앱을 끈다.

## 강동균에게 전달: 예약 이벤트 기록 방법

> 아래를 그대로 보냅니다. 내 코드가 main에 합쳐진 뒤부터 쓸 수 있습니다.

동균님, 좌석 선점(RSV-02) DB 트랜잭션에 outbox 기록 한 줄만 넣어 주세요 (concurrency.md §2.2 9번).

```java
private final OutboxRecorder outbox;   // com.jejari.event.outbox, 빈으로 주입

@Transactional
public void hold(...) {
    // 1~8. 예약 INSERT, 좌석 재고 HOLD, 카운터, 이력, saga_instance …
    outbox.recordReservation(reservationId, EventType.SEAT_HELD, new SeatHeld(...));   // 9. 맨 끝에
}
```

- **꼭 `@Transactional` 안에서** 불러 주세요. 밖에서 부르면 `IllegalTransactionStateException`이 납니다 (일부러 막아 둠). JPA·JdbcTemplate 어느 쪽 트랜잭션이든 같은 DB 연결이라 그대로 묶입니다.
- **reservation 행 INSERT·UPDATE 뒤에** 부르면 됩니다 (§0.5). 그래야 같은 예약의 이벤트 순서가 보장됩니다.
- payload는 아무 record나 넘기면 JSON으로 저장됩니다. `SEAT_HELD` 필드는 기능설계서 8장 표대로:
  `reservationId, memberId, ciHash, runId, seats[{seatId, segNo}], from, to, seatCount, amount, payDeadlineAt, source`
- Kafka 발행, 순서, 재시도, 중복 제거는 제 쪽(릴레이·Inbox)이 맡습니다. 그 뒤로는 신경 쓰지 않아도 됩니다.
- 트랜잭션이 롤백되면 이벤트도 같이 사라지니, 실패 경로에서 따로 지울 필요 없습니다.
- 자세한 설명: `docs/길동현/features/outbox-릴레이-inbox.md`

## 다음 할 일

- [x] ~~`com.jejari.event` 패키지와 이벤트 봉투·타입~~
- [x] ~~Outbox 기록 샤드 계산을 실제 Oracle로 검증 (T32)~~
- [x] ~~EVT-01~03 커밋·push (`0f9f181`)~~
- [x] ~~`features/` 기능 문서, `troubleshooting/` 문서 2건, `contribution.md` 갱신~~
- [ ] 강동균에게 위 "전달" 내용 보내기 (먼저 내 브랜치를 main에 합치기)
- [ ] Mock PG (카드번호 끝자리 규칙) → 결제 시작·확정·실패 (§4·§5)
- [ ] 강동균 예약 코드가 들어오면 Saga (EVT-05)
- [ ] 실제 `@KafkaListener` 컨슈머 재전달 테스트는 알림 컨슈머를 만들 때 함께
