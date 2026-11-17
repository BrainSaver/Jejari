# Outbox 기록 · 릴레이 · Inbox 멱등 소비 (EVT-01~03)

> **열차 예매에서 어디?** 화면에는 안 보이는 뒤쪽 "연락망"입니다. 예매가 확정·취소·만료될 때마다 알림, 좌석 반환(대기자에게 넘기기), 위험 점수 계산 같은 다른 기능에 그 사실을 전달합니다.

작성자: 길동현 · 코드 위치: `src/main/java/com/jejari/event/` (절차: `concurrency.md` §11·§12)

## 한 줄 요약

DB에 반영된 일은 **빠짐없이, 순서대로, 한 번만** Kafka 소비자에게 전달되게 하는 장치입니다.

## 어떻게 동작하나

```
[업무 TX]  예약/결제 UPDATE + outbox_event INSERT  → 커밋 (둘이 같이 성공하거나 같이 실패)
[릴레이]   샤드(0~15)별로 맨 앞 N개 잠금 → seq 순서로 Kafka 발행 → PUBLISHED 표시
[소비자]   processed_event INSERT(중복이면 건너뜀) + 업무 처리 → 커밋
```

- **Outbox**: 이벤트를 Kafka로 바로 보내지 않고 업무와 같은 트랜잭션에 "보낼 것" 행으로 남깁니다. 업무가 롤백되면 이벤트도 사라지고, 커밋되면 반드시 남습니다.
- **릴레이**: 같은 예약의 이벤트는 같은 샤드(`ORA_HASH(reservation_id, 15)`)에 들어가고, 샤드 안에서 seq 순서대로 하나씩 보냅니다.
- **Inbox**: 릴레이는 "보냈는데 표시 전에 죽으면" 다시 보냅니다(최소 한 번). 소비자가 `(그룹, event_id)`를 기록해 두 번째부터 건너뜁니다.

## 다른 파트에서 쓰는 법

```java
// 이벤트를 남길 때 (업무 트랜잭션 안에서)
@Transactional
public void hold(...) {
    // 업무 처리 …
    outboxRecorder.recordReservation(reservationId, EventType.SEAT_HELD, payload);
}

// Kafka 소비자에서 (한 번만 처리)
inboxProcessor.process(EventTopics.GROUP_SEAT_RELEASE, envelope, e -> { /* 처리 */ });
```

## 왜 이렇게 했나

- **DB 커밋 후 Kafka 직접 발행은 안 됨**: 그 사이에 죽으면 이벤트가 사라지고, 반대 순서면 롤백된 일이 알려집니다.
- **잠긴 행을 건너뛰지 않고 기다림** (`FOR UPDATE WAIT 5`, `SKIP LOCKED` 아님): 건너뛰면 같은 예약의 다음 이벤트가 먼저 나갑니다.
- **발행 실패 시 그 샤드 이번 회차 중단**: 뒤 행이 같은 예약의 다음 이벤트일 수 있습니다. 5번 실패하면 `FAILED`로 바꾸고 그 샤드만 멈춥니다.
- **1차는 인스턴스 1대가 샤드 16개 전부 담당**: 여러 대로 늘리면 Redis 리스(§11.2)만 붙이면 됩니다.

## 확인한 것 (테스트 27개)

| | 결과 |
|---|---|
| 업무 롤백 → outbox 행 | 같이 사라짐 |
| 커밋 전 릴레이 | 못 봄, 커밋 뒤 발행 |
| 릴레이 2개 동시, 40건 | 각 1번씩, seq 순서 |
| 보낸 뒤 표시 전 중단 → 재발송 | Inbox가 1번만 처리 |
| 한 샤드 FAILED | 다른 샤드는 계속 발행 |
| 일부러 넣은 순서 버그 2종 | 테스트가 모두 잡음 |

## 주의할 점

- `recordReservation`은 **트랜잭션 밖에서 부르면 예외**입니다 (`MANDATORY`). 꼭 업무 `@Transactional` 안에서.
- 소비자는 **같은 이벤트를 두 번 받을 수 있다고 가정**하고 반드시 `InboxProcessor`를 거칩니다.
- 샤드가 `FAILED`로 멈추면 로그(`outbox 샤드 n 정지`)를 보고 원인을 고친 뒤 `status='PENDING', retry_count=0`으로 되돌립니다.
- 실제로 커밋하는 테스트(`OutboxCommitInfraTest`)는 **앱(bootRun)을 끄고** 돌립니다. 앱의 릴레이가 테스트 행을 먼저 가져갑니다.
- 정책 값(배치 500, 잠금 대기 5초, 재시도 5회, 보관 24시간/8일)은 `app_policy`에서 바꿉니다.
