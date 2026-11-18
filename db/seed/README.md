# 기준 데이터 시드 (DATA-01)

담당 B 강동균 · 기능 DATA-01, 보조로 ADM-01·ADM-02의 1차 대안 (기능설계서 1.1)

열차·노선·좌석 도메인의 기준 데이터를 Oracle에 넣는 SQL입니다. 스키마의 원천은
`docs/erd/jejari-erd.html`이고, 이 폴더는 **그 스키마에 들어갈 값**만 담습니다.
스키마가 바뀌면 이 SQL도 같이 고칩니다.

## 평소에는 직접 실행할 일이 없습니다

`docker compose up -d` 로 Oracle을 처음 띄우면 `docker/oracle/init/02-seed.sh` 가
DDL 다음으로 01~05를 자동 실행합니다. 확인:

```bash
docker compose logs oracle | grep "jejari seed loaded"
```

스키마나 시드를 고친 뒤 다시 넣으려면 Oracle 볼륨을 지우고 다시 띄웁니다 (데이터 삭제됨):

```bash
docker compose rm -sf oracle && docker volume rm jejari_oracle-data && docker compose up -d oracle
```

### 시연 운행이 묵었으면 — 지우지 말고 04 → 05만 다시

시드 운행의 날짜는 **"시드를 돌린 날" 기준 상대값**입니다. 오래 띄워 둔 DB에서는
9001·9002가 이미 출발해 `DEPARTED`가 되고, **예매가 막혀 좌석 선점·결제·취소 테스트가
통째로 깨집니다.** `99_verify.sql` **24번**이 이걸 잡습니다.

날짜를 앞으로 밀기만 하면 되고, **지울 것은 없습니다** (회원·예약은 `run_id`로 매달려
있어 열차와 함께 밀립니다):

```bash
docker compose exec -T oracle bash -lc '
cd /seed
for f in 04_regular_run.sql 05_train_stop_fare.sql; do
  sqlplus -s "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
SET SQLBLANKLINES ON
ALTER SESSION SET TIME_ZONE = '"'"'Asia/Seoul'"'"';
@${f}
EXIT
SQL
done'
```

`04`가 운행일·오픈·마감을, `05`가 정차 시각을 다시 계산해 덮어씁니다.
04는 **예매 가능한 모든 날**(약 2개월)에 열차 2편씩 넣으므로, 다시 돌리면 그 창도 함께 밀립니다.
운행 번호는 `700000 + (운행일 - 2026-01-01) * 10 + train_id`로 **날짜에서 나옵니다** —
적재일 기준으로 매기면 재실행 때 같은 날짜가 다른 번호를 받아 유니크 제약에 걸립니다.
`03`의 명절 운행은 `INSERT`만 하므로 이 방법으로 밀리지 않습니다 (지금은 +30~33일이라
여유가 있습니다). 자세한 건
[트러블슈팅](../../docs/강동균/troubleshooting/20261118-시연-운행이-출발해-예매가-막힘.md).

아래는 compose 없이 직접 돌릴 때입니다.

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

# 3. 검증 — 24개 항목이 모두 OK여야 합니다
sqlplus -S jejari/비밀번호@localhost:1521/FREEPDB1 @99_verify.sql
```

`99_verify.sql`은 `SET`·`COLUMN` 같은 SQL*Plus 명령을 쓰므로 sqlplus로 실행합니다.
나머지 파일은 표준 SQL이라 다른 클라이언트로도 됩니다.

다시 넣고 싶으면 `99_reset.sql` → 1~5 다시 실행. 예약·결제 데이터가 남아 있으면
FK 때문에 reset이 실패하므로 그 데이터를 먼저 지웁니다.

## 넣는 값

| 테이블 | 건수 | 내용 |
|---|---|---|
| `station` | 5 | 서울(3900023) · 광명(3900883) · 대전(3900073) · 동대구(3900096) · 부산(3900114) |
| `route` | 1 | 경부선 (서울 → 부산) |
| `route_station` | 5 | 순번 1~5, 서울 기준 누적거리 0 / 22.0 / 159.8 / 286.7 / 417.4 km (경부고속선) |
| `car_type` | 2 | `50554101` 일반실 55석 `ABCD` · `60303101` 특실 30석 `ABC` |
| `train` | 2 | **00103** 하행(서울→부산) · **00026** 상행(부산→서울) — TAGO API의 실제 KTX 편명 |
| `train_car` | 20 | 열차당 10량 (1호차 특실, 2~10호차 일반실) |
| `seat` | 1,050 | 열차당 525석 (특실 30 + 일반 495) |
| `sale_event` | 1 | id 2 「시연 명절」 연휴 = 실행일 + 30~33일, 예매 오픈 = 다음날 07:00 |
| `purchase_limit_policy` | 7 | 행사 2 전용 4건(1회 4 · 열차당 4 · **1일 20** · 명절 8) + 상시 3건(1회 9 · 열차당 10 · 1일 20) |
| `train_run` | 11 | 명절 8건(`SCHEDULED`) + 평시 3건(9001·9002 `ON_SALE`, 9003 내일 오픈) |
| `train_stop` | 55 | 운행당 5역. 진행 방향 기준 `stop_order` 1~5. **시각은 TAGO API 실제 시각표** |
| `fare` | 40 | 역 순서쌍 20 × 등급 2. **일반실은 TAGO API 실제 운임**(서울~부산 54,400원), 특실은 ×1.4(76,200원) |

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
반올림. 서울~부산 417.4 km → 2시간 43분, 일반 55,000원 / 특실 77,000원 (실제 KTX 약 59,800원과 근접).

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

## 적재 스크립트 (CSV·API → SQL)

원본은 두 가지입니다. **파일데이터(CSV)** 는 역·노선·거리·차량유형, **오픈 API** 는 시각표·운임입니다.

```bash
node tools/fetch-train-api.js             # API에서 받아 db/seed/data/tago-train-info.json 저장
node tools/load-public-data.js            # 검사만. SQL이 원본과 다르면 종료코드 1
node tools/load-public-data.js --write    # SQL의 값 블록을 다시 생성
```

`fetch-train-api.js`는 `.env`의 `DATA_GO_KR_KEY`를 씁니다 (커밋 금지). 한 번 받아 두면
그 뒤로는 네트워크 없이 `load-public-data.js`만 돌면 됩니다 — CI도 그렇게 검사합니다.


```bash
node tools/load-public-data.js            # 검사만. SQL이 CSV와 다르면 종료코드 1
node tools/load-public-data.js --write    # SQL의 값 블록을 다시 생성
```

`01_station_route.sql` 과 `02_car_type_train_seat.sql` 의 `-- @generated:…` ~ `-- @end:…`
사이가 **CSV에서 생성되는 구역**입니다. 손으로 고치지 마세요. MERGE 문과 주석은 손으로 쓴
것이라 스크립트가 건드리지 않습니다.

역을 늘리거나 노선을 바꾸려면 `tools/load-public-data.js` 맨 위 `TARGET` 만 고치고
`--write` 로 다시 생성하면 됩니다.

CI(`.github/workflows/ci.yml`)가 매 push마다 검사 모드로 돌려서, 시드가 CSV와 어긋나면
빌드가 실패합니다.

## 스키마가 바뀌면 각자 DB에도 넣어야 합니다

**DDL은 Oracle 볼륨을 처음 만들 때만 돕니다.** 그래서 ERD가 올라가도 이미 띄워 둔 DB는
예전 스키마 그대로입니다. 테이블은 다 있어서 겉으로는 멀쩡해 보이는데, CHECK 제약이나
시퀀스가 없어 **통합 테스트가 엉뚱한 곳에서 깨집니다.**

실제로 2026-11-18에 `RiskScoreBatchInfraTest` 2개가 "expected 1 but was 0"으로 실패했는데,
원인은 `case_marker.rule_code` CHECK에 `REGULAR_INTERVAL`이 없어서(ERD v24) 마커가 저장되지
않은 것이었습니다. 테스트 메시지만 봐서는 알 수 없습니다.

```bash
# 이미 데이터가 있어 볼륨을 지우고 싶지 않을 때 — 델타만 (다시 실행해도 안전)
PW=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2)
docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle   bash -lc "sqlplus -S -L jejari/$PW@localhost:1521/FREEPDB1" < db/patch/v24.sql

# 아니면 처음부터 (데이터 삭제됨)
docker compose rm -sf oracle && docker volume rm jejari_oracle-data && docker compose up -d oracle
```

## 오픈 API 적용 (2026-11-18)

역·노선·거리·차량유형은 **파일데이터(CSV)** 로 받아 쓰고 있었지만, **정차 시각과 운임은
공공데이터가 아니라 우리 계산식**이었습니다 (거리÷2.7km/분 + 정차 2분, 2,800원 + 125원/km).
국토교통부 (TAGO) 열차정보 오픈 API가 실제 시각표와 운임을 줘서 그 두 칸을 실제 값으로 바꿨습니다.

| | 포털 ID | 가져온 것 | 들어간 곳 |
|---|---|---|---|
| 국토교통부_(TAGO)_열차정보 | [15098552](https://www.data.go.kr/data/15098552/openapi.do) | 열차번호 · 정차 시각 · 일반실 운임 | `train`, `train_stop`, `fare` |

### 바뀐 값

| | 전 (계산식) | 후 (API 실제값) |
|---|---|---|
| 열차 번호 | 101 · 102 (지어낸 값) | **00103 · 00026** |
| 서울~부산 소요 | 2시간 52분 | **3시간 7분** (09:28 → 12:35) |
| 서울~부산 일반실 | 55,000원 | **54,400원** |
| 서울~광명 일반실 | 5,600원 | **7,500원** |

### 어떻게 골랐나

- **열차**: 5역에 모두 서는 순수 KTX 중 09시 이후 첫 편 (하행 00103, 상행 00026).
  보기 좋은 시간대를 쓰려고 새벽 편(05시대)을 피했습니다.
- **시각**: API가 **중간역 시각을 주지 않아서** 역 쌍을 돌며 모았습니다.
  어떤 역의 도착은 (기점 → 그 역), 출발은 (그 역 → 종점) 조회에서 나옵니다.
- **날짜**: 시·분만 쓰고 날짜는 각 운행의 `run_date`에서 붙입니다. 우리 운행일이
  적재일 기준 상대값이라 조회한 날짜를 그대로 쓰면 맞지 않습니다.
- **운임**: 같은 구간에 여러 편이 있으면 가장 많이 나온 값을 씁니다 (특별열차 운임에 끌려가지 않게).
  **특실은 API가 주지 않아 일반실 × 1.4** 로 우리가 정합니다.

## 공공데이터 적용 (2026-11-16 완료)

기획서 데이터 활용 1번대로 공공데이터포털에서 받아 **실제 값을 넣었습니다.**
원본 CSV는 `db/seed/data/` 에 그대로 둡니다 — 어느 값이 어디서 왔는지 추적하려는 것입니다.

| 데이터셋 | 포털 ID | 가져온 것 | 들어간 곳 |
|---|---|---|---|
| 철도운영정보_역코드_20240901 | 15138467 | 역코드(STN_CD) · 역명 | `station` |
| KTX 노선별 역정보_20251121 | 15127571 | 노선명 · 순번 | `route`, `route_station.station_order` |
| 철도운행거리_경부고속선_20240130 | 15128657 | 서울 기준 누적 거리 | `route_station.distance_from_origin_km` |
| 차세대예약발매_차량유형코드_20260922 | 15137680 | 차량유형코드 · 차량유형명 · 좌석수 | `car_type` |

### 대조하면서 고친 것

**1. 역코드는 7자리였다.** 처음엔 4자리(`0001`)로 추측했고, 중간에 "3자리(001)"로 정정했는데
그것도 틀렸습니다. 실제 `STN_CD`는 **7자리**(서울 `3900023`)입니다. 두 번 다 추측이었고
원본을 받고 나서야 맞췄습니다. 역코드 체계를 추측하지 말아야 한다는 교훈입니다.

**2. 거리가 경부선(재래선) 값이었다.** 처음 넣은 441.7 km는 재래선 영업거리인데, KTX는
경부고속선을 탑니다. 실제 고속선 서울~부산은 **417.4 km** 입니다. 거리에서 운임이 나오므로
운임 40행도 전부 바뀌었습니다 (일반 58,000 → **55,000원**, 실제 KTX 약 59,800원과 가까워졌습니다).

**3. 거리 원본은 역×역 매트릭스다.** `기준역명` 한 컬럼 + 역 29개 컬럼입니다.
기준역명이 `서울`인 행을 읽어 누적거리로 옮겼습니다.

**4. 차량유형 좌석수가 달랐다.** 추측한 일반 56 / 특실 36석 대신 원본의
`50554101` "KTX일반55,간이2" **55석**, `60303101` "KTXⅡ,특30(3)" **30석** 을 씁니다.
247건 중 차량유형명에 등급이 분명히 적힌 둘을 골랐습니다. 좌석 수가 열 문자 수로
나누어떨어지지 않게 돼서(55 = 13열 ABCD + 14열 ABC) 검증 10번을
`CEIL(n/k) + FLOOR(n/k)` 공식으로 바꿨습니다.

**5. 노선명은 '경부선'이 맞았다.** KTX 노선별 역정보의 노선명이 `경부선`입니다.
거리만 고속선 기준이고 노선명은 그대로 둡니다.

### 원본에 없어 우리가 정한 값

`seat_grade` · `seat_columns`(좌석 등급·열 배치), 열차 편성(10량), 운행 일정,
운임 산출식. DB설계서에 이미 '생성'으로 표기된 것들입니다.
`station.latitude`/`longitude`는 위 데이터셋에 없어 비워 둡니다 (선택 컬럼).

## 다른 파트에 넘기는 것

| 상대 | 내용 |
|---|---|
| C 길동현 | 결제 금액은 `fare`에서 읽습니다. 선점 시점 운임으로 고정(`reservation.total_amount`, 기능설계서 RSV-02) |
| D 강다인 | ~~`cancel_fee_policy` 상시 정책이 없습니다~~ → **해결 (ERD v21, 2026-11-17).** 강다인 씨가 비율을 정해 주셔서 `sale_event_id` NULL 세트 5건을 예시 행으로 넣었습니다: 3일 전 0% · 1~3일 5% · 3시간~1일 10% · 3시간 이내 20% · 출발 후 40%. 이미 쓰고 있는 Oracle은 `db/patch/v19-v21.sql`을 돌리세요 |
| D 강다인 | 취소표 제안(§9.5)의 대기자 한도 검사에 **`PER_DAY`가 추가**됐습니다. `PER_RUN`·`PER_HOLIDAY`만 보면 1일 한도를 넘겨 제안됩니다 |
| C 길동현 | `PER_DAY` scope를 `concurrency.md` §10에 넣었습니다 (구매일 기준). 절차 문서를 고쳤으니 봐 주세요 |
| E 조영준 | Docker Compose에서 Oracle 컨테이너에 `TZ=Asia/Seoul`이 필요합니다 (동시성 §0.7, 검증 20번) |
| A 김성원 | 대기열 테스트용 `ON_SALE` 운행은 `run_id = 9001`(하행)·`9002`(상행)입니다 |
