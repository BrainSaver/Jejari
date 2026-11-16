# 기준 데이터 시드 (DATA-01)

담당 B 강동균 · 기능 DATA-01, 보조로 ADM-01·ADM-02의 1차 대안 (기능설계서 1.1)

열차·노선·좌석 도메인의 기준 데이터를 Oracle에 넣는 SQL입니다. 스키마의 원천은
`docs/erd/jejari-erd.html`이고, 이 폴더는 **그 스키마에 들어갈 값**만 담습니다.
스키마가 바뀌면 이 SQL도 같이 고칩니다.

## 실행 순서

```bash
# 1. 스키마 (ERD 편집기가 생성한 DDL)
sqlplus -S jejari/비밀번호@localhost:1521/FREEPDB1 @docs/erd/jejari-erd.ddl.sql

# 2. 기준 데이터
cd db/seed
sqlplus -S jejari/비밀번호@localhost:1521/FREEPDB1 @01_station_route.sql
sqlplus -S jejari/비밀번호@localhost:1521/FREEPDB1 @02_car_type_train_seat.sql
sqlplus -S jejari/비밀번호@localhost:1521/FREEPDB1 @03_sale_event_run.sql
sqlplus -S jejari/비밀번호@localhost:1521/FREEPDB1 @04_regular_run.sql
sqlplus -S jejari/비밀번호@localhost:1521/FREEPDB1 @05_train_stop_fare.sql

# 3. 검증 — 23개 항목이 모두 OK여야 합니다
sqlplus -S jejari/비밀번호@localhost:1521/FREEPDB1 @99_verify.sql
```

`99_verify.sql`은 `SET`·`COLUMN` 같은 SQL*Plus 명령을 쓰므로 sqlplus로 실행합니다.
나머지 파일은 표준 SQL이라 다른 클라이언트로도 됩니다.

다시 넣고 싶으면 `99_reset.sql` → 1~5 다시 실행. 예약·결제 데이터가 남아 있으면
FK 때문에 reset이 실패하므로 그 데이터를 먼저 지웁니다.

## 넣는 값

| 테이블 | 건수 | 내용 |
|---|---|---|
| `station` | 5 | 서울(0001) · 광명(0008) · 대전(0010) · 동대구(0015) · 부산(0020) |
| `route` | 1 | 경부선 (서울 → 부산) |
| `route_station` | 5 | 순번 1~5, 기점 누적거리 0 / 22.0 / 166.3 / 323.9 / 441.7 km |
| `car_type` | 2 | `KTX-GEN` 일반실 56석 `ABCD` · `KTX-FST` 특실 36석 `ABC` |
| `train` | 2 | 101 하행(서울→부산) · 102 상행(부산→서울) |
| `train_car` | 20 | 열차당 10량 (1호차 특실, 2~10호차 일반실) |
| `seat` | 1,080 | 열차당 540석 (특실 36 + 일반 504) |
| `sale_event` | 1 | id 2 「시연 명절」 연휴 = 실행일 + 30~33일, 예매 오픈 = 다음날 07:00 |
| `purchase_limit_policy` | 7 | 행사 2 전용 4건(1회 4 · 열차당 4 · **1일 20** · 명절 8) + 상시 3건(1회 9 · 열차당 10 · 1일 20) |
| `train_run` | 11 | 명절 8건(`SCHEDULED`) + 평시 3건(9001·9002 `ON_SALE`, 9003 내일 오픈) |
| `train_stop` | 55 | 운행당 5역. 진행 방향 기준 `stop_order` 1~5 |
| `fare` | 40 | 역 순서쌍 20 × 등급 2 |

## 값을 이렇게 정한 이유

**좌석 ID를 시퀀스가 아니라 계산식으로** — `seat_id = train_id × 100000 + car_no × 1000 + 좌석순번`.
동시성 테스트(§16 T1 "같은 좌석 동시 선점 100건")가 좌석 ID를 코드에 고정해 쓰기 때문에,
다시 시드해도 같은 좌석이 같은 ID여야 합니다. 시퀀스를 쓰면 매번 달라집니다.

**좌석 번호는 "열번호 + 열문자"** — `car_type.seat_columns`를 그대로 규칙으로 씁니다.
일반실 `ABCD`면 1~56번 좌석이 `1A`…`14D`가 되고, 양 끝 열문자(A·D)가 창측입니다.
일반실은 창측이 절반, 특실(`ABC`)은 2/3입니다.

**`stop_order`는 진행 방향 기준** — 동시성 §2.4의 구간 수가
`하차역 stop_order − 승차역 stop_order`이므로, 상행(102)은 `route_station.station_order`를
뒤집어 부산이 1번이 됩니다. 뒤집지 않으면 상행 예약의 구간 수가 음수가 됩니다.

**시연용 행사(id 2)를 새로 만드는 이유** — DDL의 예시 행 `sale_event` 1(2026 추석)에 묶인
매수 제한 정책은 `valid_to`가 2026-09-27로 이미 지났습니다. 동시성 §10.6은 "지금 시각이
`valid_from`~`valid_to` 안인 행"만 후보로 보기 때문에, 그대로 두면 매수 제한이 **아무것도
적용되지 않습니다**(§10.6 표의 "정책이 없으면 제한 없음"). 시연·테스트 기간을 덮는 행사와
정책이 필요합니다.

**상시 정책(`sale_event_id` NULL) 2건을 두는 이유** — 개발용 운행 9001은 행사에 묶이지
않았습니다. §10.6은 행사 정책이 없으면 상시 정책을 쓰므로, 상시 정책이 없으면 이 운행에서
매수 제한이 걸리지 않습니다. `PER_HOLIDAY`는 행사가 있어야 의미가 있어 상시에 두지 않았습니다.

**소요 시간·운임은 산출식** — 소요 시간 = 진행 방향 누적거리 ÷ 2.7 (km/분, 약 162 km/h)
+ 중간 정차 2분씩. 운임 일반실 = 2,800원 + 125원/km, 특실 = 일반실 × 1.4, 100원 단위
반올림. 서울~부산 441.7 km → 2시간 52분(주행 164분 + 정차 8분), 일반 58,000원 / 특실 81,200원.

**매수 제한·예매 오픈은 코레일 실제 규칙** — 개인 구입 제한은 열차당 최대 10석·하루 최대
20석이고, 일반 승차권은 출발일 2개월 전 오전 7시에 오픈합니다
([KTX 예매 안내](https://kbthink.com/life/leisure/ktx-booking-guide.html),
[코레일 예매제도 개선](https://www.koreaittimes.com/news/articleView.html?idxno=138642)).
"하루 20매"를 담을 `PER_DAY` scope가 스키마에 없어서 ERD v11 패치로 추가했습니다
(`purchase_limit_policy`·`purchase_counter`·Redis `limit:day:*`, 절차는 동시성 §10).
평시 운행의 오픈 시각은 손으로 넣지 않고 `ADD_MONTHS(run_date, -2) + 07:00`으로 계산하고,
`status`도 그 값과 지금 시각을 비교해 정합니다 (검증 22·23번). 명절 특별예매는 통상 더
조이므로 행사 정책(1회 4 · 열차당 4 · 명절 전체 8)이 상시 정책보다 우선합니다 (§10.6).

**날짜를 상대값으로** — 고정 날짜로 두면 며칠 뒤 모든 운행이 과거가 되어 시연이 안 됩니다.
그래서 실행일 기준으로 계산하고, 이미 있는 행사·운행의 날짜는 덮어쓰지 않습니다
(날짜를 다시 잡으려면 `99_reset.sql`).

## 확인 필요 (공공데이터 실제 파일 대조 전)

지금은 **구조만 맞춘 시연용 값**입니다. 기획서 데이터 활용 1번(한국철도공사 차량유형코드·
좌석수, 역간 운행거리)을 실제로 내려받은 뒤 아래를 대조하고, 다르면 이 SQL을 고칩니다.

| 항목 | 지금 값의 근거 | 대조할 것 |
|---|---|---|
| `station_id` | 코레일 NAT 역 코드 4자리로 가정 (0001·0008·0010·0015·0020) | 실제 코드 체계와 자릿수. 다르면 `VARCHAR2(10)` 안에서 교체 |
| `distance_from_origin_km` | 경부선 영업거리 | 역간 운행거리 파일의 누적거리 |
| `car_type_code`, `seat_count` | `KTX-GEN`/`KTX-FST`, 56/36석으로 가정 | 차량유형코드 파일의 실제 코드값과 좌석 수 |
| `latitude`, `longitude` | 비워 둠 (선택 컬럼) | 역 좌표가 있으면 채움 |
| 상시 `PER_RESERVATION` 9매 | 코레일 1회 예매 인원 상한으로 알려진 값 — **검색으로 확인 못 함** | 실제 1회 매수 상한 |

## 다른 파트에 넘기는 것

| 상대 | 내용 |
|---|---|
| C 길동현 | 결제 금액은 `fare`에서 읽습니다. 선점 시점 운임으로 고정(`reservation.total_amount`, 기능설계서 RSV-02) |
| D 강다인 | `cancel_fee_policy`는 DDL 예시 5건이 모두 `sale_event_id = 1`(지난 행사)입니다. 시연 행사 2나 평시 운행에서 수수료를 계산하려면 상시 정책(`sale_event_id` NULL) 세트가 필요합니다 — CNL-01·02 담당이 정해 주세요 |
| D 강다인 | 취소표 제안(§9.5)의 대기자 한도 검사에 **`PER_DAY`가 추가**됐습니다. `PER_RUN`·`PER_HOLIDAY`만 보면 1일 한도를 넘겨 제안됩니다 |
| C 길동현 | `PER_DAY` scope를 `concurrency.md` §10에 넣었습니다 (구매일 기준). 절차 문서를 고쳤으니 봐 주세요 |
| E 조영준 | Docker Compose에서 Oracle 컨테이너에 `TZ=Asia/Seoul`이 필요합니다 (동시성 §0.7, 검증 20번) |
| A 김성원 | 대기열 테스트용 `ON_SALE` 운행은 `run_id = 9001`(하행)·`9002`(상행)입니다 |
