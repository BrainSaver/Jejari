# 강동균 개인 기여 정리

> 산출물을 한눈에 보려면 [`features/산출물.html`](features/산출물.html) (브라우저로 열기).
> 기능별로 URL이 갈립니다 — `#DATA-01` · `#RSV-02` · `#LOCK` · `#ERD`

> 면접에서 "본인이 한 일"을 바로 꺼내 쓸 수 있게, 작업할 때마다 조금씩 채웁니다.

## 1. 담당 범위

- 직책: 팀원 (B), ERD 반영 담당
- 범위: 좌석 선점 동시성, 락 방식 비교(비관적·낙관적·Redis 분산락), 1인 매수 제한, 선점 만료·결제 기한 자동 반환, 열차·노선·좌석 도메인, 공공데이터 적재, 관리자 예매 현황

## 2. 핵심 수치

| 지표 | 목표 | 결과 | 측정 방법 |
|---|---|---|---|
| 중복 예약 | 0건 | **0건** (T1: 같은 좌석 100명 동시 → 성공 1, 거절 99, DB 예약·재고 행 1개) | `SeatHoldInfraTest` (실제 Oracle·Redis), Lua 단독은 `python redis/test/lua_test.py` |
| 락 방식별 TPS | 비교 수치 확보 | 같은 좌석 100명: A 255 / B 1,457 / **C 2,732** TPS. DB 왕복 201 → **2회** | `python bench/lock_compare.py` (워밍업 1회 + 5회 중앙값) |
| 락 방식별 DB 왕복 (HTTP 수준) | 비교 수치 확보 | 같은 좌석 100명: A 101 / B 119 / **C 1회**. TPS는 B 2,128 / C 1,724로 **뒤집힘** | `node bench/web/server.js` → 버튼 (k6, 3회차 중앙값) |
| 기한 초과 좌석 반환율 | 100% | **100%** (기한 지난 HOLD 20건 → 20건 만료, 좌석 행·카운터·Redis 한도 전부 반환) | `SeatReleaseInfraTest` (실제 Oracle·Redis) |

## 3. 설계 결정

결정 하나당 한 칸. "왜 이렇게 했는가"를 면접에서 그대로 말할 수 있게 적습니다.

### 결정 1. 좌석 ID를 시퀀스가 아니라 계산식으로 만든다

- 상황: 기준 데이터 시드를 다시 넣을 때마다 좌석 ID가 달라지면, 좌석 ID를 코드에 고정해
  쓰는 동시성 테스트(§16 T1 "같은 좌석 동시 선점 100건")가 매번 깨진다.
- 고려한 대안: ① Oracle 시퀀스 ② `(train_id, car_no, seat_no)` 자연키를 PK로 ③ 계산식
- 선택과 이유: 계산식 `seat_id = train_id × 100000 + car_no × 1000 + 좌석순번`.
  시드를 다시 넣어도 같은 좌석이 같은 ID다. 자연키를 PK로 쓰면 `seat_inventory`·
  `reservation_seat`의 FK가 3컬럼으로 늘어나 복합 FK가 더 무거워진다.
- 감수한 단점: 호차당 좌석 999석, 열차 ID가 10만 미만이라는 암묵적 상한이 생긴다.
  지금 편성은 호차당 최대 56석이라 여유가 크지만, 상한을 넘기면 식을 바꿔야 한다.
- 관련 코드·커밋: `db/seed/02_car_type_train_seat.sql`

### 결정 2. 시연용 명절 행사와 상시 매수 제한 정책을 따로 둔다

- 상황: DDL 예시의 `sale_event` 1(2026 추석)에 묶인 매수 제한 정책은 `valid_to`가
  2026-09-27로 이미 지났다. 동시성 §10.6은 지금 시각이 유효 기간 안인 정책만 후보로
  보므로, 그대로 두면 매수 제한이 **아무것도 적용되지 않는다**(§10.6 "정책이 없으면 제한 없음").
  1인 매수 제한이 내 핵심 수치인데 검증 자체가 불가능해진다.
- 고려한 대안: ① 예시 행의 날짜를 미래로 수정 ② 시연용 행사·정책을 새 ID로 추가
- 선택과 이유: ②. 예시 행은 ERD 편집기의 `samples`에서 나오므로 날짜를 고치면 스키마
  변경(패치)이 되고, 다른 팀원 브라우저의 예시 값까지 건드린다. 시드는 값의 문제라
  SQL에서 해결하는 것이 맞다. 행사에 묶이지 않은 운행용으로 상시 정책(`sale_event_id` NULL)도
  함께 넣었다.
- 감수한 단점: 유효한 정책이 행사용·상시 두 세트로 늘어나, §10.6의 "행사 정책 우선, 없으면
  상시" 선택 규칙이 실제로 맞게 구현됐는지 꼭 테스트해야 한다.
- 관련 코드·커밋: `db/seed/03_sale_event_run.sql`

### 결정 3. 1일 매수 제한을 스키마에 새로 넣고, 세는 단위를 구매일로 한다

- 상황: 코레일 개인 구입 제한은 열차당 10석·하루 20석인데, 스키마의 매수 제한 scope는
  `PER_RESERVATION`/`PER_RUN`/`PER_HOLIDAY`뿐이라 **하루 한도를 담을 자리가 없었다.**
  1인 매수 제한이 내 핵심 수치라 실제 규칙을 못 담으면 비교할 기준이 사라진다.
- 고려한 대안: ① 하루 한도를 포기하고 열차당 한도만 쓴다 ② `PER_DAY`를 추가하고
  세는 단위를 **운행일**로 한다 ③ `PER_DAY`를 추가하고 **구매일**로 한다
- 선택과 이유: ③. 이 규칙은 "하루에 몰아 사는 것"을 막는 장치(코레일 "실수요자 보호")라서
  세는 단위가 타는 날이 아니라 사는 날이다. 이 프로젝트의 목표인 암표·매크로 억제와도 맞다.
  ①은 핵심 수치를 포기하는 것이라 제외.
- 감수한 단점: 감소할 때 "지금 날짜"를 쓰면 안 되고 그 예약의 `created_at` 날짜를 읽어야 한다.
  자정을 넘겨 만료·취소되는 예약이 다음 날 카운터를 깎는 버그가 생길 수 있어 테스트(T37)를
  따로 뒀다. 운행일 해석도 가능해서 기능설계서 미정 사항에 올렸다.
- 관련 코드·커밋: ERD v11 패치(`docs/erd/jejari-erd.html`), `concurrency.md` §10,
  `db/seed/03_sale_event_run.sql`

### 결정 4. 예매 오픈 시각을 값이 아니라 규칙으로 넣는다

- 상황: 코레일 일반 승차권은 출발 2개월 전 오전 7시 오픈. 시드에 오픈 시각을 손으로 적으면
  운행을 추가할 때마다 규칙과 어긋날 수 있고, 어긋나도 아무도 모른다.
- 고려한 대안: ① 운행마다 오픈 시각을 직접 적는다 ② `run_date`에서 계산한다
- 선택과 이유: ②. `sale_open_at = ADD_MONTHS(run_date, -2) + 07:00`으로 계산하고
  `status`도 그 값과 지금 시각을 비교해 정한다. 검증 스크립트 22·23번이 규칙 위반을 잡는다.
- 감수한 단점: 명절 특별예매는 일괄 오픈이라 이 규칙이 안 맞아서, 명절 운행은
  `sale_event.sale_open_at`을 복사하는 예외 경로가 따로 생겼다.
- 관련 코드·커밋: `db/seed/04_regular_run.sql`, `db/seed/03_sale_event_run.sql`

### 결정 5. 좌석 선점은 Redis 1차 + DB PK 최종으로 간다 (락 방식 비교 결과)

- 상황: 같은 좌석을 동시에 고른 요청 중 하나만 통과시켜야 한다. 비관적 락·낙관적 락·
  Redis 1차 중 뭘 쓸지 수치로 정해야 했다 (§17.1, 내 핵심 수치).
- 고려한 대안: ① 비관적 락(`SELECT FOR UPDATE`) ② 낙관적 락(`version` 조건부 UPDATE)
  ③ Redis `hold.lua` 1차 + DB PK 최종
- 선택과 이유: ③. 좌석 1,080석이 든 Oracle에 세 방식을 **같은 입력으로** 돌려 재 보니
  같은 좌석 100명 경쟁에서 2,732 TPS로 ①의 10.7배였다. 그런데 **고른 진짜 이유는 TPS가
  아니라 DB 왕복이 201회에서 2회로 줄어든 것**이다. 패배자가 Redis에서 걸러져 DB에 아예
  가지 않는다. 명절 예매는 인기 열차 한 편에 수만 명이 몰리는 상황이라 DB가 버티는 게 먼저다.
  중복 예약은 세 방식 모두 0건이어서 정확성은 변별력이 없었다.
- 감수한 단점 (예상과 달랐던 것):
  - **경쟁이 적으면 ③이 ②보다 느리다** (756 vs 1,256 TPS). 좌석이 안 겹치면 Redis 왕복이
    순수 오버헤드다. "Redis를 끼우면 항상 빠르다"가 틀렸다.
  - **꼬리 지연(p99)은 ②가 낫다** (43.5ms vs 74.3ms). ③은 패배자가 빨리 끝나 평균을
    끌어내리는 대신 승자들의 INSERT가 몰려 꼬리가 길어진다.
  - **HTTP 수준(k6, 2026-11-17)에서는 극한 경쟁에서도 TPS가 ②에 진다** (B 2,128 vs C 1,724).
    요청마다 HTTP·Spring 왕복이 10~15ms 붙으면서 ③이 아끼던 Redis 왕복 한 번의 비중이 작아졌다.
    **그래도 DB 왕복은 119 → 1이다.** "③이 가장 빠르다"가 아니라 "③만 DB를 지킨다"가 맞는 설명이다
    ([k6 재측정](features/락비교-k6-재측정.md)).
  - 그래도 1차는 ③으로 통일한다. 운영 중에 "지금 경쟁이 심한가"를 미리 알 수 없고,
    코드가 두 갈래가 되면 버그 자리가 는다. 바꿔 끼우는 `SeatHoldStrategy`는 **설계만 해 두고
    프로덕션에는 넣지 않았다** — A·B안에서는 Redis `seat:hold` 키가 비어 배치도·해제·결제가
    어긋난다. 측정용 입구(`com.jejari.bench`, 개발 모드 전용)를 따로 뒀다.
  - Redis 장애 시 선점 자체가 막힌다. DB 최종 판정은 살아 있지만 1차가 없으면 경쟁이
    전부 DB로 간다.
- 관련 코드·커밋: `bench/lock_compare.py`, `bench/README.md`, `src/main/resources/lua/seat/hold.lua`

## 4. 만든 기능

기능마다 `features/`에 짧은 기능 문서를 남깁니다 (CONTRIBUTING.md 4장).

| 기능 | 문서 |
|---|---|
| DATA-01 기준 데이터 시드 (역·노선·열차·좌석·운행·운임·매수 제한) | [기준데이터-시드](features/기준데이터-시드.md) |
| RSV-02 좌석 선점·매수 한도 1차 판정 (Redis Lua 8개) | [좌석선점-lua](features/좌석선점-lua.md) |
| §17.1 락 방식 비교 + SeatHoldStrategy 설계 | [락방식-비교](features/락방식-비교.md) |
| RSV-02 좌석 선점 API (멱등·재선점·매수 카운터) + 판매 중 운행 제공 | [좌석선점-api](features/좌석선점-api.md) |
| RSV-03 만료 스캐너 · RSV-05 결제 전 취소 · 결제 실패 해제(Saga용) | [좌석해제-만료-취소](features/좌석해제-만료-취소.md) |
| TRN-01~03 역·운행 검색·운임, RSV-01 좌석 배치도 (seatmap 캐시, 행동 로그 4종) | [열차조회-좌석배치도](features/열차조회-좌석배치도.md) |
| 결제·확정 취소가 부르는 좌석 단계 (PAY-01 마감 연장, PAY-02 좌석 확정, CNL-02 좌석 반환) | [좌석-결제취소-연결](features/좌석-결제취소-연결.md) |
| ADM-02 판매 오픈 스케줄러 (train_run.status 시각 전이) | [판매오픈-스케줄러](features/판매오픈-스케줄러.md) |
| ADM-03 실시간 예매 현황 (좌석·분당 건수·대기열·결과 모름 결제) | [실시간-예매현황](features/실시간-예매현황.md) |
| §17.1 락 비교 k6 HTTP 수준 재측정 + 측정 웹 러너 | [락비교-k6-재측정](features/락비교-k6-재측정.md) |
| WL-01·02·03 취소표 대기 신청·조회·제안 (seat-release 소비자 = §14.3 보장 경로) | [취소표-대기-제안](features/취소표-대기-제안.md) |
| └ 결과를 설치 없이 보는 페이지 (문서 사이트) | [락비교-결과](features/락비교-결과.html) |

## 5. 다른 파트와의 연결

| 상대 | 주고받는 것 | 약속한 내용 |
|---|---|---|
| C 길동현 | 운임 | 결제 금액은 `fare`에서 읽고, 선점 시점 운임을 `reservation.total_amount`로 고정 (기능설계서 RSV-02) |
| D 강다인 | 취소 수수료 정책 | `cancel_fee_policy` 예시 5건이 모두 지난 행사(1번)에 묶여 있어 상시 정책 세트가 필요 — CNL 담당이 정함 |
| E 조영준 | Oracle 컨테이너 | `TZ=Asia/Seoul` 필수 (동시성 §0.7, 시드 검증 20번) |
| A 김성원 | 테스트용 운행 | 지금 `ON_SALE`인 운행은 `run_id = 9001` 하나 |
| C 길동현 | 좌석 해제 | 결제 실패(§5.2)는 `SeatReleaseService.releaseInCurrentTx(R, ReleaseReason.PAY_FAILED)`를 payment FAILED 기록과 같은 트랜잭션에서 호출. 두 번 불러도 결과가 같고, Redis 보상은 커밋 뒤 자동 |
| C 길동현 | 결제 시작·승인의 좌석 단계 | §4는 reservation 전이 뒤 `SeatPaymentService.extendHoldInCurrentTx(R, 새 마감)`, §5.1은 reservation 락 뒤 `confirmSeatsInCurrentTx(R)`. false면 좌석을 잃은 것이라 롤백 (§4 거절 / §5.1 확정 실패 경로). Redis는 커밋 뒤 자동 ([문서](features/좌석-결제취소-연결.md)) |
| D 강다인 | 확정 취소의 좌석 반환 | CONFIRMED → CANCELED 1행 뒤 `SeatReleaseService.returnSoldSeatsInCurrentTx(R)` → SOLD 행 삭제·카운터 감소. 돌려받은 `eventPayload()`에 reason·cancelFee·refundAmount를 더해 `RESERVATION_CANCELED`로. 커밋 뒤 좌석 키 `REOFFER:R`·한도 감소 자동 |
| A 김성원 | 판매 중 운행·입장 토큰 | `JdbcOnSaleRunProvider`가 `train_run`을 읽음 (개발 모드는 대용품 유지), 선점 API가 `QueuePassService.verify` 호출 (팀-확인-요청 3·4번) |
| A 김성원 | ERD v13 | `queue:member` 값에 ciHash, `queue.entry_ttl_sec`, 취소표 대기 `priority_at`·`penalty_ms` 반영 (팀-확인-요청 1·2·12번) |
| D 강다인 | ERD v13 | `risk_score_history_seq` 시퀀스 추가 → 점수 저장 때 이력도 기록 |
| D 강다인 | 행동 로그 | 내 API 4곳에 `BehaviorLog.mark`: VIEW_TRAIN·VIEW_SEAT·SEAT_HOLD·CANCEL. 실패 요청도 `errorCode`와 함께 남김 (MongoDB 적재 확인) |
| D 강다인 | ERD v15 | 회원가입·로그인용 시퀀스 2개, Redis 키 3개, `app_policy` 한도 3개, `login_attempt.result` BANNED |
| C 길동현 | ERD v19 | `payment_seq`(CACHE 100)·`payment_method_seq`·`cancellation_seq` 추가 |
| D 강다인 | ERD v19 | RISK-04: `suspicious_case_seq`·`case_marker_seq`, 대상당 열린 케이스 1건 유니크 인덱스, `case_marker.session_id` NULL 허용. 곧 쓸 `waitlist_seq`·`admin_action_seq`. 조회 인덱스 `member(ci_hash)`·`payment_method(method_hash)`·`behavior_log{device_hash,ts}`. Redis 키 `risk:dirty`·`risk:dirty:work`·`risk:batch:lock` |
| A 김성원 | 판매 오픈 자동화 | `JdbcOnSaleRunProvider`가 보는 운행이 시각에 맞춰 늘고 줄어든다. `9003`은 오픈 시각(내일 07:00)에 자동으로 열림 |
| E 조영준 | 부하 시나리오 | 오픈 순간 트래픽을 실제로 재현할 수 있다 — `sale_open_at`을 몇 초 뒤로 넣으면 그 시각에 열림 |
| A 김성원 | 대기열 숫자 | 현황 API가 `QueueKeys`의 `queue:{runId}`·`queue:active:{runId}`·`queue:throughput:{runId}`를 읽기만 함. 키 이름이 바뀌면 알려 주세요 |
| C 길동현 | 결과 모름 결제 | 현황 API의 `payments.unknown`이 §6.2 건수다. ADM-09 화면의 진입 숫자로 쓰면 됨 |
| D 강다인 | 취소 건수 | 현황의 `rates.cancels`는 `cancellation` 행 기준이라 **만료가 빠진다** (만료는 돈이 안 오가 행을 만들지 않음). 취소율을 볼 때 주의 |

## 6. 트러블슈팅

| 날짜 | 제목 | 수치 |
|---|---|---|
| 2026-11-16 | [커넥션 풀 중첩 조회 멈춤](troubleshooting/20261116-커넥션-풀-중첩-조회-멈춤.md) | T1 실패(30초 타임아웃) → 통과 |
| 2026-11-17 | [앱이 떠 있으면 통합테스트가 조용히 깨짐](troubleshooting/20261117-앱이-떠-있으면-통합테스트가-조용히-깨짐.md) | 통합 3~5개 실패(매번 다름) → **351개 전부 통과** |

## 7. 예상 질문

- Q. 
  - A. 
