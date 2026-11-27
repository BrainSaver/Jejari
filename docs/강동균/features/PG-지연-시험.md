# PG가 느리거나 멈출 때 — 결제 때문에 다른 기능까지 멈추나

> 작성: 강동균 · 2026-11-27 · 결제 코드 담당 길동현 님 대신 진행 · 측정: 내 PC(로컬) · [원자료](PG-지연-시험-원자료/)
> 관련: `loadtest/mock-pg/server.js` · `HttpPgClient` · `PgBulkhead` · `loadtest/scenarios/15_pg_slow.js`

## 한 줄 요약

**PG가 응답하지 않으면 결제 요청이 요청 스레드를 붙잡고, 다 차면 결제와 상관없는 조회까지 멈춘다.**
응답 한도를 30초로 두면 실제로 스레드 200개가 다 찼고(조회 p99 14.7초), **3초로 두면 최고 68개**(조회 p99 0.56초)였다.
앱 기본값이 3초라 지금 설정 그대로 버틴다. 동시 승인 상한(`PgBulkhead`)은 한도가 길 때의 두 번째 방어다.
장애 중 결제 300건은 모두 "결과 모름"이 됐다가 **스캐너가 약 1분 만에 전부 확정**했고, 돈만 빠지거나 두 번 팔린 좌석은 **0건**이었다.

## 왜 했나

- 지금까지 잰 결제 시간은 앱 안의 가짜 PG(`MockPgClient`)가 **0초 만에 답한다**고 가정한 값이었다 (11-26 공용 서버 결제 p95 0.27~0.32초 포함)
- 실제 PG는 한 건에 0.5~2초가 걸리고, 명절 오픈처럼 몰리면 느려지거나 응답이 안 온다. 은행이 안 느려져도 문제가 된다:
  **기다리는 스레드 수 ≈ 초당 결제 수 × 한 건 걸리는 시간**. 초당 200건 × 1초면 Tomcat 기본 스레드 200개가 다 찬다
- 실제 PG 테스트 모드는 요청 수 한도가 낮고 장애를 우리가 고를 수 없어 부하 시험에 못 쓴다 → **느려지는 정도를 우리가 정하는 가짜 PG 서버**를 따로 띄워 **네트워크(HTTP)로** 부른다

## 구조

```
k6 (구매자 300명 + 조회만 하는 50명)
   │
   ▼
앱 ── 결제: 멱등 키 → 결제 단계 차단 → [PgBulkhead 자리 잡기] → DB 트랜잭션(결제 행) → 커밋
   │                                                                              │
   │                                                       HttpPgClient ── HTTP ──▼
   │                                                    (연결 1초 · 응답 한도)   가짜 PG 서버 (Node)
   │                                                                          처리 칸·지연·응답 없음
   └─ 응답 한도 초과 → 결과 모름(REQUESTED) → 마감에 스캐너가 PG에 조회 → 확정·실패 (§6.2, 원래 있던 경로)
```

| 바꾼 것 | 내용 |
|---|---|
| 가짜 PG 서버 `loadtest/mock-pg/server.js` | 외부 패키지 없는 Node. 카드 끝자리 규칙은 `MockPgClient`와 같음(0 거절, 8 늦은 승인, 9 결과 모름). 처리 칸(capacity)을 넘으면 줄을 서서 몰릴수록 느려짐, 지연 범위, 응답을 안 주는 비율(hangRate). `PUT /_config`로 실행 중에 바꾸고 `/_stats`로 최대 동시·줄을 봄 |
| `HttpPgClient` | 앱이 PG를 HTTP로 부름. 연결·응답 한도, 시간 초과·연결 실패·5xx는 **결과 모름**(PG는 승인했을 수 있으니 실패로 치지 않음). 지표 `jejari_pg_call_seconds{op,result}`, `jejari_pg_inflight` |
| `PgBulkhead` | PG 동시 승인 상한. **결제 행을 만들기 전에** 자리를 잡아, 거절되면 DB에 아무것도 안 남고 예약은 HOLD 그대로 → 같은 멱등 키로 다시 내면 처음처럼 처리. 거절은 503 `PAYMENT_BUSY` + `Retry-After` 3초 |
| 오류 로그 | `Retry-After`가 붙은 503(예상된 거절)은 ERROR·스택트레이스를 남기지 않음. 상한 시험 한 번에 1,451개가 쌓였었다 |
| 설정 (`application.yml` `jejari.payment.pg.*`) | `mode` mock(기본)·http, `read-timeout-ms` 3000, `max-concurrent` 0(끔), `busy-retry-after-sec` 3 |

기본값이 `mode: mock`이라 테스트·시연·공용 서버는 아무것도 바뀌지 않는다.

- 절차 문서: [`concurrency.md` §4.4](../../concurrency.md) (응답 한도·동시 상한), §0.6·§4.2-2에 한 줄씩
- 안전장치: 이 기기 밖의 PG 주소는 **https만** 받는다 (승인 본문에 카드번호가 들어감, 앱 시작 때 막음)
- 테스트: `HttpPgClientTest` 6개(응답 해석·시간 초과·5xx·연결 실패·https), `PgBulkheadTest` 3개, `PaymentInfraTest`에 상한이 찼을 때(503 + Retry-After 3초, 결제 행 0, HOLD 그대로, 멱등 키 지움, PG 안 감 → 자리가 나면 같은 키로 승인) — 실제 Oracle·Redis 24/24

## 시험

- 부하: 구매자 300명이 30초 동안 도착(초당 10명) → 로그인 → 대기열 → 배치도 → 선점 → 결제. 그동안 조회만 하는 50명이 계속 역 목록·운행 상세
- 비교 지표는 **조회만 하는 사람의 응답 시간**. 결제가 스레드를 다 잡으면 이 값이 같이 나빠진다
- PG 장애(`down`): PG는 승인을 처리하지만 응답을 30초 동안 안 준다 (처리는 됐는데 응답이 끊긴 경우)
- 회차마다 새 운행(빈 좌석 525석)과 새 계정. 같은 PC에서 앱·DB·k6를 같이 돌림

| | ① 기준 (PG 0초) | ② 장애 · 한도 30초 | ③ 장애 · 30초 + 상한 40 | ③′ ③ + Retry-After·로그 정리 | ④ 장애 · **한도 3초** |
|---|---|---|---|---|---|
| Tomcat 바쁜 스레드 최고 | — | **200 / 200** | 190 | 112 | **68** |
| PG 응답 기다리는 요청 최고 | — | 194 | 54¹ | 40 | 53 |
| 조회 p95 | 0.88초 | 0.77초 | 0.62초 | 0.43초 | 0.32초 |
| 조회 p99 | 1.6초 | **14.7초** | 2.9초 | 0.89초 | **0.56초** |
| 조회 최대 | 2.5초 | **16.3초** | 5.3초 | 1.9초 | 1.3초 |
| 결제 결과 | 승인 300 | 결과 모름 300 | PG까지 감 80 · 상한에 막혀 포기 220 | 같음 (80 · 220) | 결과 모름 300 |
| 결제 체감 p95 | 2.3초 | 31초 | 36초 | 43초 | **3.8초** |

¹ 상한은 승인만 센다. 이 값에는 앞 회차의 결과 모름을 스캐너가 조회한 요청이 섞였다.

### 읽는 법

1. **②에서 스레드 200개가 다 찼다.** PG 응답을 기다리는 요청이 194개였고, 그동안 들어온 조회는 10초 넘게 기다렸다(p99 14.7초).
   조회 p95가 그대로인 건 다 찬 구간이 75초 중 일부라서다
2. **DB 커넥션은 PG를 기다리는 동안 잡히지 않는다.** 결제 30건이 PG를 5초씩 기다릴 때(PG 대기 30·스레드 31) 커넥션 사용은 0~5개, 대부분 0~1개였다
   (PG 호출이 DB 트랜잭션 밖에 있는 설계, `concurrency.md §0.6`). 묶이는 건 스레드뿐이다
3. **응답 한도가 가장 큰 방어다 (④).** 결제 하나가 스레드를 3초 넘게 잡지 못하니 초당 10건 × 3초 ≈ 30~60개에서 멈춘다.
   사용자는 3.8초 만에 "처리 중"을 받고 결과는 스캐너가 정한다
4. **상한(③)은 한도가 길 때를 막아 주지만, 거절당한 사람의 재시도가 다시 부하가 된다.** 1~2초마다 다시 보내게 하자 503이 1,451번 나왔고
   503마다 ERROR 로그가 남았다. `Retry-After` 3초를 알려 주고 로그를 없애자 스레드 최고가 190 → 112, 조회 p99가 2.9 → 0.89초(③′)
5. 결제 체감이 ③·③′에서 더 긴 건 재시도 대기(최대 5번)까지 넣은 시간이라서다. 상한에 걸린 사람은 좌석이 HOLD로 남아 기한 안에 다시 낼 수 있다

### 정합성 (네 회차 모두)

| 항목 | 결과 |
|---|---|
| 돈만 빠진 결제 (승인됐는데 예약 미확정) | **0** |
| 확정인데 승인 결제 없음 | **0** |
| 예약 하나에 승인 2건 이상 | **0** |
| 같은 좌석 확정 2건 이상 | **0** |
| 좌석 SOLD 수 = 확정 좌석 수 | 회차마다 같음 (300 = 300 등) |
| ② 결과 모름 300건 | next_check 10:22:44부터 **10:23:37까지 전부 승인 → 확정** (스캐너가 PG에 조회) |

## 한계 (발표에서 먼저 말할 것)

- **내 PC 한 대**에서 앱·Oracle·k6를 같이 돌렸다. 이 PC는 결제가 초당 15건쯤에서 DB 커넥션(20개)이 먼저 찬다
  (PG 0초로도 400명/5초, 250명/15초는 503이 났다). 그래서 "느린 PG(2~4초)"만으로는 스레드 200개를 채울 만큼 결제를 몰 수 없었고,
  **PG가 아예 응답하지 않는 장애**로 원리를 보였다
- 회차마다 한 번씩만 쟀다. 조회 p95처럼 작은 차이(0.32~0.88초)는 PC 상태에 따라 흔들릴 수 있다. **p99·최대·스레드 수처럼 큰 차이만 결론으로 쓴다**
- 측정(1초 간격)도 스레드가 필요해서, 스레드가 다 찬 동안에는 간격이 벌어진다. 다 찬 시간은 기록보다 길다
- 공용 서버에서는 11-27 오후에 쟀다 (아래 "공용 서버 결과"). 서버는 11-19부터 앱 1대(`jejari@8080`)라 스레드·상한이 로컬과 같은 단위다

## 다시 돌리기

```bash
docker compose --profile pg up -d mock-pg
./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1 --jejari.payment.pg.mode=http --jejari.payment.pg.read-timeout-ms=30000"
#   상한: --jejari.payment.pg.max-concurrent=40   (응답 한도 기본 3000)
cd loadtest && ./run.sh pgslow -e RUN_ID=<빈 좌석 많은 서울→부산 운행> -e PG_PROFILE=down -e BUYERS=300 -e BROWSERS=50 -e SPREAD_SEC=30 -e DURATION_SEC=75
# 스레드·PG 대기·커넥션: curl -s 127.0.0.1:8080/actuator/prometheus | grep -E "^tomcat_threads_busy|^jejari_pg_inflight|^hikaricp_connections_active"
```

- `PG_PROFILE`: fast(0초) · normal(0.5~1.5초) · slow(2~4초) · broken(1~3초, 10% 응답 없음) · down(전부 30초 응답 없음)
- 로컬 가입 한도에 걸리면 Redis `rate:signup:*` 키를 지운다 (한 번에 계정 300개를 만든다)
- 결과 모름은 결제 마감 뒤 스캐너가 정리하니 정합성은 몇 분 뒤에 본다
- 운행 9002처럼 방향이 반대(부산→서울)면 배치도가 400 → 사전 점검이 알려 준다

## 공용 서버에서 (절차)

서버 일은 GCP 콘솔 브라우저 SSH에서, k6는 내 PC에서. 가짜 PG 관리 주소(8090)는 서버 밖으로 안 열려 있어서
**PG 설정은 서버에서 `curl`로 바꾸고 k6에는 `PG_PROFILE`을 주지 않는다**. 30분쯤 걸리고, 그동안 **실제 사람 결제도 가짜 PG 서버를 탄다**
(down 동안에는 그 결제도 결과 모름 → 스캐너가 확정) → 팀 채널에 먼저 알린다.

**계정**: 공용 서버는 가입이 시간당 계정 50개쯤에서 막혀 시나리오 15가 직접 못 만든다 → 관리자 API `POST /api/v1/admin/auth-relax`(내 IP만, 2시간, 가입 5000)로 풀고
시나리오 11 `ACCOUNTS=prepare`로 **`RUN_TAG=pg1` 100개**를 미리 만들어 둠 (11-27, `perf1`은 로그인 401이라 안 씀) → 15에서 `ACCOUNTS=existing`.
사람 = 카드 한 장이라 카드 명의 상한(3)에 안 걸린다. 사유 칸은 영문만 (Git Bash에서 한글이 깨져 400).

```bash
# [서버] 0. 자동 배포 멈춤 + 가짜 PG 켜기 + 앱을 http 모드로 (응답 한도는 기본 3초 = 로컬 ④와 같은 조건)
sudo systemctl disable --now jejari-autodeploy.timer
cd ~/Jejari && C="docker compose -f compose.yaml -f deploy/compose.server.yaml"
$C --profile pg up -d mock-pg && sleep 3 && curl -s 127.0.0.1:8090/_stats; echo
grep -q '^JEJARI_PG_MODE=' .env || echo 'JEJARI_PG_MODE=http' >> .env
sudo systemctl restart jejari@8080 && until curl -fs localhost:8080/actuator/health >/dev/null; do sleep 3; done; echo UP
```

```bash
# [내 PC] 1. 빈 운행 고르기 · perf1 계정 확인
BASE_URL=https://<서버> loadtest/tools/server_perf.sh find
```

```bash
# [서버] 2-①. 기준: PG 0초
curl -s -X PUT 127.0.0.1:8090/_config -H 'Content-Type: application/json' -d '{"latencyMinMs":0,"latencyMaxMs":0,"capacity":200,"hangRate":0}'; echo
# [서버] 측정 중 스레드·PG 대기 (5초마다, 따로 창 하나 더)
while true; do date +%T; curl -s localhost:8080/actuator/prometheus | grep -E "^tomcat_threads_busy|^jejari_pg_inflight|^hikaricp_connections_active"; sleep 5; done | tee ~/pg-$(date +%H%M).txt
```

```bash
# [내 PC] 2-①. 측정 (PG_PROFILE 없이)
cd loadtest && ./run.sh pgslow -e BASE_URL=https://<서버> -e I_OWN_THIS_TARGET=yes -e RUN_ID=<운행> -e ACCOUNTS=existing -e RUN_TAG=pg1 -e BUYERS=100 -e BROWSERS=50 -e SPREAD_SEC=10 -e DURATION_SEC=75
# 100명/10초 = 초당 10명 → 로컬 300명/30초와 같은 도착 속도
```

```bash
# [서버] 2-④. 장애: 승인은 하되 응답을 30초 안 줌 → 내 PC에서 같은 명령을 다른 빈 운행으로 한 번 더
curl -s -X PUT 127.0.0.1:8090/_config -H 'Content-Type: application/json' -d '{"latencyMinMs":0,"latencyMaxMs":0,"capacity":200,"hangRate":1,"hangMs":30000}'; echo
```

```bash
# [서버] 3-1. 가짜 PG가 다시 답하게 (이 줄만. 되돌리기와 한 번에 붙이지 않는다)
curl -s -X PUT 127.0.0.1:8090/_config -H 'Content-Type: application/json' -d '{"latencyMinMs":0,"latencyMaxMs":0,"capacity":200,"hangRate":0}'; echo
```

3-2. **결과 모름이 0이 될 때까지 기다린다.** 스캐너는 결제 **마감(선점 + 10분)이 지난 뒤에** PG에 묻는다 → 장애 측정 끝나고 약 10분.
0이 되기 전에 mock으로 되돌리면 스캐너가 "PG가 모르는 주문"(`PG_NOT_RECEIVED`)으로 실패 처리해 가짜 PG는 승인했는데 좌석이 풀린다.
서버 DB 없이도 내 PC에서 볼 수 있다: 시험 계정으로 로그인해 `GET /api/v1/reservations`의 `PAYMENT_PENDING` 수를 센다 (11-27에 이렇게 함).
3-1이 앞서야 하는 이유: 장애 설정 그대로면 스캐너의 조회도 응답을 못 받아 계속 모름으로 남는다.

```bash
# [서버] 4. 되돌리기 (꼭, 3-2에서 0을 본 뒤)
cd ~/Jejari && C="docker compose -f compose.yaml -f deploy/compose.server.yaml"
sed -i '/^JEJARI_PG_MODE=/d' .env
sudo systemctl restart jejari@8080 && until curl -fs localhost:8080/actuator/health >/dev/null; do sleep 3; done; echo UP
$C stop mock-pg
sudo systemctl enable --now jejari-autodeploy.timer && sudo systemctl start jejari-autodeploy && cat ~/jejari-app/autodeploy.state
```

볼 것: 조회만 하는 사람의 p99·최대(①과 ④ 차이), 스레드 최고(`tomcat_threads_busy`), 정합성 0건 (로컬 표와 같은 기준).

## 공용 서버 결과 (2026-11-27 14:31~14:46)

서버 일은 조영준 님(브라우저 SSH), k6·확인은 강동균 PC. 구매자 100명/10초(`pg1` 계정, `ACCOUNTS=existing`) + 조회만 50명 × 75초. 응답 한도 기본 3초, 동시 상한 끔.

| | 기준 · 운행 731175 | 장애 · 운행 731149 |
|---|---|---|
| 가짜 PG | 서버 기본값 **0.5~1.5초** (0초로 바꾸는 단계를 안 넣어서 — 실제 PG에 더 가까움) | 승인은 하되 응답을 30초 안 줌 |
| 결제 | 100/100 승인 | 100건 결과 모름 → 마감(14:45:54) 뒤 **30초 안에 100건 승인 확정** |
| 결제 응답 p95 · 최대 | 2.20초 · 2.83초 | **3.27초 · 3.38초** (3초 한도에서 끊김) |
| 조회만 하는 사람 p95 · p99 · 최대 | 0.28 · 0.85 · 2.65초 | **0.14 · 0.50 · 2.23초** |
| 조회 실패 | 0 / 3,456 | 0 / 3,565 |
| 남은 좌석 | 525 → 425 | 525 → 425 |

- **결론**: PG가 멈춰도 결제와 상관없는 사람은 느려지지 않았다. 로컬 ④(응답 한도 3초)와 같은 결과를 서버에서도 확인
- 못 본 것: 서버 스레드 수(`tomcat_threads_busy`)는 서버 안에서만 보여 이번엔 안 쟀다. 결제 행 수는 DB가 아니라 예약 상태(회원 API)로 확인
- 시험 중 다른 사람 결제가 있었다면 그것도 가짜 PG를 탔다 (장애 1분 30초 동안은 결과 모름 → 마감 뒤 확정)

## 다음

- [x] (2026-11-27) 결제 화면: `PAYMENT_BUSY`면 `Retry-After`만큼(±20%) 뒤 같은 멱등 키로 자동 재시도 — 최대 10번, 결제 기한이 10초 넘게 남을 때만, 화면을 떠나면 멈춤 (`frontend/src/user/payBusy.js` + 단위 테스트 5개, 가짜 서버 응답으로 브라우저 확인: 503 두 번 → 1초 간격 재시도 3번·키 1개 → 결과 화면)
- [x] 기능설계서 9장 오류 코드 표에 `PAYMENT_BUSY`(503) 추가 — 11-27 팀장 허가
- [x] 공용 서버에서 한 번 (11-27 14:31~14:46, 서버 일 조영준 님) → 위 "공용 서버 결과". 되돌림·자동 배포 다시 켬 확인
- [ ] 상한 값: 40은 "응답 한도가 길 때 스레드를 지키는" 시험값. 3초 한도에서는 꺼 둬도 버텼으니 기본은 끔 유지
