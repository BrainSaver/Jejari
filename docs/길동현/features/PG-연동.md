# PG(결제대행사) 연동

작성자·담당: 길동현 (11-27부터 강동균이 넣은 `HttpPgClient`·`PgBulkhead`까지 PG 쪽 전체를 맡음) · 코드 위치: `src/main/java/com/jejari/payment/pg/` (`PgClient`·`MockPgClient`·`HttpPgClient`), `PaymentService`, `PaymentRecheckScanner`, `PgBulkhead` · 절차: `docs/concurrency.md` §4~§6, §4.4

> **열차 예매에서 어디?** [결제하기]를 누른 뒤 앱이 카드사(PG)에 "이 금액 승인해 주세요"라고 묻는 부분입니다. 실제 카드사 대신 가짜 PG를 씁니다.

## 한 줄 요약

결제 흐름은 `PgClient` 경계 하나만 보고, 그 뒤에 앱 안 가짜 PG(`mock`, 기본)나 네트워크 PG(`http`)를 끼운다. PG 답은 **승인 · 거절 · 모름** 셋이고, **모르면 실패로 치지 않고 나중에 다시 묻는다.**

## PG에 하는 일 4가지 (`PgClient`)

| 메서드 | 언제 | 답 |
|---|---|---|
| `approve(주문번호, 금액, 카드)` | 결제 시작 | APPROVED → 예매 확정 · DECLINED → 예매 취소·좌석 반환 · UNKNOWN → 그대로 두고 기다림 |
| `inquire(주문번호)` | 결과 모름일 때 스캐너가 다시 물음 | APPROVED / FAILED / NOT_FOUND(받은 적 없음) / UNKNOWN |
| `blockOrder(주문번호)` | PG가 "받은 적 없다"고 할 때 | 그 주문을 막아, 늦게 도착한 승인 요청도 거절되게 함 |
| `refund(주문번호, 거래ID, 금액, 환불키)` | 확정 취소(강다인), 늦은 승인 환불 | 같은 환불 키는 한 번만 처리 → 재시도해도 두 번 환불 안 됨 |

- 주문 번호 = `payment_id`. PG는 같은 주문 번호 승인을 한 번만 처리한다고 가정 → 승인 요청을 다시 보내도 돈이 두 번 안 빠짐
- PG 호출은 전부 **DB 트랜잭션 밖**에서 한다 (§0.6). 느린 PG가 DB 락을 붙잡지 않게

## 가짜 PG: 카드 끝자리로 결과 정하기 (`MockPgClient`, 기본)

| 끝자리 | 결과 | 보는 경로 |
|---|---|---|
| `0` | 거절 | 예약 취소, 좌석은 대기자 먼저 |
| `9` | 응답 없음, 조회해도 계속 모름 | 결제 마감까지 기다렸다 관리자 확인 |
| `8` | 응답 없음, 조회하면 승인 | 스캐너가 확정 (마감 뒤면 늦은 승인 환불, PAY-05) |
| 나머지 | 승인 | 예매 확정 |

- 결제 화면의 시험 카드 버튼은 `4111` + 회원 ID + 끝자리로, 사람마다 번호가 다르다 (카드 명의 상한·위험 점수가 한 카드에 몰리지 않게, 강동균 `3ceb91a`)

## "결과 모름"을 다루는 법

```
approve → UNKNOWN (시간 초과·응답 없음)
  → 예약은 PAYMENT_PENDING 그대로, 만료시키지 않음
  → PaymentRecheckScanner가 다시 물음 (inquire)
       APPROVED  → 확정 (마감이 지났으면 예약은 되살리지 않고 전액 자동 환불)
       FAILED    → 실패 처리, 좌석·매수 한도 되돌림 (Saga 보상)
       NOT_FOUND → blockOrder로 막은 뒤 실패 처리
       UNKNOWN   → 다음에 또 물음, 오래가면 관리자 확인(ADM-09)
```

- 왜: 모르는 걸 실패로 치면 **돈은 빠졌는데 좌석이 남에게 가는** 일이 생긴다. 좌석을 조금 더 묶어 두는 쪽을 골랐다

## 네트워크 PG와 몰림 방어 (11-27 강동균이 처음 넣음 → 길동현이 넘겨받음)

| 무엇 | 하는 일 | 기본값 |
|---|---|---|
| `HttpPgClient` | PG를 HTTP로 부름. 연결 1초·응답 3초 한도. **시간 초과·연결 실패·5xx는 결과 모름**으로 돌려 위 스캐너가 처리. 이 기기 밖 주소는 https만 (카드번호가 본문에 들어감) | 꺼짐 (`JEJARI_PG_MODE=mock`) |
| `PgBulkhead` | PG 동시 승인 수 상한. 넘치면 결제 행을 만들기 전에 503 `PAYMENT_BUSY` + `Retry-After` 3초. DB에 아무것도 안 남아 같은 멱등 키로 다시 보내면 처음처럼 처리 | 꺼짐 (`JEJARI_PG_MAX_CONCURRENT=0`) |
| 결제 화면 자동 재시도 | `PAYMENT_BUSY`면 `Retry-After`만큼 기다렸다 같은 멱등 키로 다시 (최대 10번, 결제 기한이 10초 넘게 남을 때만) | 켜짐 (상한이 꺼져 있으면 일어나지 않음) |
| 카드 명의 상한 | 한 카드에 붙는 서로 다른 명의 수가 상한 이상이면 422 `PAYMENT_CARD_LINK_LIMIT` (도용 카드 막기) | 꺼짐 (`payment.max_ci_per_card=0`) |

- 지금 `http`로 붙는 것은 부하 시험용 가짜 PG 서버(`loadtest/mock-pg/server.js`)뿐. 실제 PG 테스트 모드를 붙이면 `HttpPgClient`의 요청·응답 모양만 바꾸면 되고 결제 흐름은 그대로다
- 측정 (로컬, PG가 멈췄을 때): 응답 한도 30초면 요청 스레드 200/200이 다 차고 조회 p99 14.7초, **3초면 최고 68개·0.56초**, 정합성 어긋남 0건 → 응답 한도가 1차 방어, 동시 상한은 2차 방어. 자세히: [강동균 PG-지연-시험](../../강동균/features/PG-지연-시험.md)

## 주의할 점

- 결제 실패 뒤에는 같은 예약으로 다시 결제할 수 없다 (다시 예매). 대신 `PAYMENT_BUSY`·카드 명의 상한은 예약이 HOLD 그대로라 바로 다시 결제할 수 있다
- 통합 테스트들이 같은 카드(`4111…1111`)를 쓰므로 로컬·테스트에서는 카드 명의 상한을 0(끔)으로 둔다

## 확인하는 법

```powershell
.\gradlew infraTest --tests "com.jejari.payment.*"   # Docker 필요, 앱은 끄고
```

`PaymentInfraTest`(중복 결제 0·실패 시 복구·늦은 승인 환불·상한이 찼을 때 포함 24개), `HttpPgClientTest`, `PgBulkheadTest`, `CardLinkLimitInfraTest`. 손으로 눌러 보기: [http/결제-흐름.http](../http/결제-흐름.http)

관련: [결제-멱등-saga](결제-멱등-saga.md) · 강동균 변경 알림 `docs/강동균/길동현-알림-결제-코드-20261127.md`
