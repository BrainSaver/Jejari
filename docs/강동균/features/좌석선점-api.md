# RSV-02 좌석 선점 API

작성자: 강동균 · 코드 `com.jejari.seat`, `com.jejari.train` · 절차 원천 `docs/concurrency.md` §1·§2·§3·§10·§14.3

## 한 줄 요약

같은 좌석에 100명이 동시에 눌러도 **1명만** 잡히고, 진 99명은 **DB에 한 줄도 쓰지 않고** 바로 거절된다.

## 어떻게 동작하나

```
POST /api/v1/reservations  (JWT + X-Queue-Token + Idempotency-Key)
  → 입장 토큰 검사 (QueuePassService.verify, 대기열 우회 차단)
  → 운행 ON_SALE·승하차역·좌석·운임 검증, 1회 한도(9매)
  → 예약 ID 발급 (reservation_seq) → idem 키 SET NX (같은 키 재요청이면 기존 예약 반환)
  → 한도 1차 limit_incr.lua (열차당·1일·명절)
  → 좌석 경쟁 hold.lua  ── 지면: 한도·idem 되돌리고 409, DB 쓰기 0건
  → [이긴 요청만] DB 트랜잭션: 만료 HOLD 재선점(§3) → reservation → seat_inventory
                             → reservation_seat → purchase_counter → outbox SEAT_HELD → 이력 → Saga
  → 201 { reservationId, status: HOLD, payDeadlineAt, totalAmount, seatIds }
```

DB가 거절하면(한도 초과·PK 충돌 등) 롤백하고 Redis 좌석 키·한도·idem 키를 되돌린다 (§2.3).

## 왜 이렇게 했나

- **Redis 1차 + DB 최종**: 락 비교(§17.1)에서 같은 좌석 100명일 때 DB 왕복이 201회 → 2회. 진 사람이 DB에 가지 않는다.
- **카운터를 정렬해서 갱신**: 재선점은 새 예약 증가 + 이전 예약(다른 사람) 감소가 한 트랜잭션에 섞인다.
  `(scope, ref, ci)` 순서로 갱신해 두 트랜잭션이 서로를 기다리는 데드락을 막는다 (§10.3).
- **SQL은 JdbcTemplate**: concurrency.md의 조건부 UPDATE·`FOR UPDATE`·`ORA_HASH`를 그대로 옮기려고 JPA 대신 네이티브 SQL.
- **판매 중 운행(`JdbcOnSaleRunProvider`)**: 대기열이 `train_run.status`를 직접 보게 함 (김성원 요청 4번). 개발 모드에선 기존 대용품을 씀.

## 주의할 점

- 운임은 [가정] 승객 유형(어린이·경로) 할인 없이 등급별 운임 그대로.
- 구간 재고는 OFF라 구간 번호는 항상 0 (K = 좌석 수, §2.4).
- 재선점으로 만료시킨 이전 예약의 Redis 한도는 커밋 직후 앱이 되돌린다(빠른 경로). 보장 경로(`seat-release` 소비자)는 아직 없음 — 길동현·강다인 파트.
- outbox 기록(`ReservationOutbox`)은 결제·취소 쪽이 생기면 공용 모듈로 합칠 것.

## 테스트 (`SeatHoldInfraTest`, `./gradlew infraTest`)

| # | 확인 |
|---|---|
| T1 | 같은 좌석 동시 100건 → 성공 1, `SEAT_ALREADY_TAKEN` 99, 예약·재고 행 1개, 진 요청의 idem·한도 원복 |
| T2 | 같은 멱등 키 재요청 → 같은 reservation_id, 카운터 1번만 |
| T3 | 만료된 2석 예약의 1석을 남이 가져감 → 이전 예약 EXPIRED, HOLD 행 0, 카운터 2 감소, outbox PREEMPTED |
| T5 | 결제 중(PAYMENT_PENDING) 좌석은 마감이 지나도 재선점 불가 |
| T33 | Redis 한도가 DB보다 크게 남아도 자가 복구 |
| - | 1회 9매 초과, 열차당 10매 초과 거절 / 마감 시각 복사본 일치 / shard_no = ORA_HASH(TO_CHAR(R), 15) / 입장 토큰 없으면 403 |

## 트러블슈팅

T1을 처음 돌렸을 때 커넥션 풀이 30초 동안 멈췄다. 운행 조회 RowMapper 안에서 정차역을 다시 조회해,
커넥션 하나를 쥔 채 하나를 더 빌리고 있었다. 동시 요청 32개가 풀 10개를 모두 쥐고 서로를 기다렸다 →
조회를 둘로 나눠 해결. → [troubleshooting/20261116-커넥션-풀-중첩-조회-멈춤.md](../troubleshooting/20261116-커넥션-풀-중첩-조회-멈춤.md)
