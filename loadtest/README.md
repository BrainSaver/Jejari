# 제자리 부하 테스트 (k6)

기획서의 성과 목표를 수치로 검증하는 k6 시나리오 모음입니다.
**직접 만든 시스템(로컬·스테이징)에만** 사용하세요. `BASE_URL`이 로컬/내부 주소가 아니면
`I_OWN_THIS_TARGET=yes`를 지정하기 전에는 실행되지 않도록 막아 두었습니다.

## 실제 구현과의 관계 (2026-11 반영)

이 폴더는 **저장소에 들어와 있고, 구현된 대기열(`src/main/java/com/jejari/queue/`)에 맞춰 고쳤습니다.**

| 구분 | 상태 | 시나리오 |
|---|---|---|
| **실제 서버와 동작** | 대기열 진입·번호표·이탈, 입장 토큰으로 보호되는 좌석 배치도, 개발용 관리 API | 1, 6, 8, 9 |
| **실제 서버 + Oracle** | 회원 인증(본인인증·가입·로그인)은 Oracle에 쓰고 읽음 (member 모듈) | 10 |
| **실제 서버 전체 흐름** | 로그인(JWT) → 열차 조회 → 대기열 → 좌석 배치도 → 좌석 선점 → 결제(모의 PG). 예약·결제 API(B·C)가 main에 들어와 실제 앱으로 끝까지 돈다 | 11 |
| **Mock 서버로만 동작** | 좌석 선점·결제·취소·취소표(다른 파트 구현 전). 실제 서버에 돌리면 사전 점검이 "API가 아직 없다"고 알려 주고 멈춥니다 | 2, 3, 4, 5, 7 |

- **인증**: 실제 앱은 아직 로그인이 없어 개발 모드에서 `X-Member-Id` 헤더로 회원을 구분합니다(`AUTH_MODE=member-header`, 기본). JWT가 붙으면 `-e AUTH_MODE=jwt`로 바꿉니다(`config.js`).
- **API 경로·응답 필드**는 `config.js`에 실제(`[실제]`)와 가정(`[가정]`)으로 나눠 적어 두었습니다. 가정 부분은 해당 파트가 구현하면 이 파일만 고치면 됩니다.
- **`k6/queue/`와의 관계**: 같은 대기열을 CLI 위주로 검증하는 별도 시나리오 모음이고 문서(`k6/queue/README.md`)의 수치가 그쪽 기준입니다. 이쪽은 같은 검증을 창(앱)으로 조작하고 결과를 비교하기 위한 것입니다. 한쪽 시나리오를 고치면 다른 쪽도 확인하세요.
- **사전 점검**: 모든 시나리오는 시작 전에 진입 요청을 한 번 해 보고, 서버가 안 떠 있음·개발 모드 아님·다른 서버가 포트를 씀·운행이 판매 중이 아님·Redis가 꺼짐을 구분해 알려 주고 멈춥니다.
- **개발용 관리 API**(앱의 개발 모드 전용, 인증 없음): 시나리오가 Redis에 직접 접속하지 않고 준비를 합니다. 앱은 로컬 서버에 실행하기 전에 `POST /api/v1/dev/reset`(대기열·위험 점수·정책 캐시 초기화)을 자동으로 부릅니다.

  | API | 하는 일 |
  |---|---|
  | `POST /api/v1/dev/reset` | 대기열·위험 점수·정책 캐시 키 삭제 (수십만 개도 1초 안) |
  | `PUT /api/v1/dev/risk/dev-members?from=&count=&score=` | 개발용 회원 N명에게 같은 위험 점수 |
  | `PUT /api/v1/dev/policy/{key}` | 정책 값 설정 (앱은 최대 1초 뒤 반영) |

**실제 앱으로 돌리기** (팀 로컬 인프라 사용 — `docker/README.md`)

```bash
# 1) .env 준비(.env.example 복사 후 비밀번호 채우기, .env는 커밋 금지) 뒤 인프라를 켠다
docker compose up -d
# 2) 앱을 개발 모드로 띄운다 (저장소 루트에서. 운행 9001이 판매 중(시드의 ON_SALE 운행))
./gradlew bootRun --args="--jejari.dev-mode=true --jejari.queue.dev-on-sale-runs=9001"
# 3) 이 앱(데스크톱) 또는 CLI로 실행. 환경 설정의 대상 URL은 http://localhost:8080, RUN_ID는 9001
./app.sh            # Windows는 app.bat
./run.sh 8 -e PEAK_RATE=4000 -e STEPS=4        # 진입 처리 한계
./run.sh 9                                       # 순번 조정 정확도
```

IDE에서 `JejariApplication`을 실행한다면 프로그램 인자에 `--jejari.dev-mode=true --jejari.queue.dev-on-sale-runs=9001`을 넣습니다.
개발 모드는 로그인 없이 `X-Member-Id` 헤더로 회원을 구분하고 `/api/v1/dev/**`를 엽니다. 운영(`jejari.dev-mode=false`)에서는 존재하지 않습니다.
좌석 선점·결제·취소표 API는 다른 파트가 구현하기 전이라 실제 앱에는 없습니다(시나리오 2~5, 7은 Mock 서버로).

## 데스크톱 앱 (창으로 조작 · 내 PC 또는 VM에서 실행)

```bash
./app.sh            # Windows는 app.bat   (Python 3.8+ 와 tkinter 필요, 별도 패키지 설치 없음)
```

Windows는 `app.bat`을 더블클릭하면 검은 cmd 창 없이 앱만 뜹니다(`pythonw` 사용). 앱이 시작되지 않으면 `~/.jejari_loadtest_error.log`에 오류가 기록됩니다.

tkinter가 없으면: Windows는 Python 설치 시 기본 포함 / macOS는 `brew install python-tk` / Ubuntu는 `sudo apt install python3-tk`.

앱은 세 개의 탭으로 구성됩니다.

| 탭 | 내용 |
|---|---|
| **테스트** | 시나리오 선택 → `가볍게/보통/명절급` 프리셋 또는 슬라이더·숫자 입력 → `▶ 시작`. 동시 VU·요청/초·실패율·p95와 시나리오 핵심 지표(예: `seat_success`)가 실시간으로 갱신되고, 끝나면 통과/기준 미달/오류가 표시됩니다 |
| **환경 설정** | 실행 위치(이 PC / VM SSH), 대상 서버 URL, 테스트 계정 생성 |
| **결과 비교** | 실행이 끝날 때마다 `results/history.json`에 자동 저장. 기록 2개를 선택하면 **전 → 후** 변화율(좋아짐 초록 / 나빠짐 빨강)을 보여주고, CSV로 내보낼 수 있습니다 |

**결과 파일 위치**: 실행이 끝나면 k6 요약이 이 폴더의 `results/<시나리오>_<시각>.json`에 저장되고(`종료 결과` 탭의 `결과 폴더 열기` 버튼), 비교용 이력은 `results/history.json`에 쌓입니다. VM 모드에서는 요약 파일이 VM의 `results/`에 저장됩니다.

**로그 창**: `▶ 시작`(또는 계정 만들기·업로드)을 누르면 로그가 **별도 창**으로 열립니다. 창을 닫아도 내용은 유지되고, 테스트 탭 위쪽의 `📄 로그 창` 버튼으로 다시 열 수 있습니다. 로그는 드래그로 선택해 `Ctrl+C`, 우클릭 메뉴, `복사`/`전체 복사` 버튼으로 복사할 수 있고, 위로 스크롤하거나 글자를 선택 중이면 자동 스크롤이 멈춥니다. `종료 결과` 표도 같은 방식으로 복사됩니다.

**원인 안내**: 실행이 실패하면 결과 배너 아래와 로그 창(`[진단]`)에 원인을 한두 줄로 알려 줍니다. 서버 연결 거부·시간 초과·주소 오류, 계정 파일 없음, k6/SSH 문제, 그리고 시나리오 수치(중복 예약, 좌석 미복구, 중복 결제, 대기 순서 위반, 토큰 우회 통과 등)처럼 **확실히 아는 경우에만** 표시하고, 원인을 모르면 아무것도 적지 않습니다. 새 패턴은 `app/core.py`의 `LOG_RULES`·`METRIC_RULES`에 추가하면 됩니다.

**센 부하에서 앱이 느려질 때**: k6와 mock 서버를 같은 PC에서 돌리면 CPU를 서로 뺏습니다. 그래서 k6·mock은 낮은 우선순위로 실행하고, 로그는 초당 80줄까지만 화면에 보여 주며(나머지는 원인 진단용으로만 보관), 수치가 `보통`의 1.5배를 넘으면 경고합니다. 그래도 목표가 크면 `dropped_iterations`가 늘고 결과를 믿기 어려우니, 서버와 k6를 다른 PC로 분리하거나 VM 실행을 쓰세요.

**화면이 낮을 때**: 세로 공간이 모자라면 그래프를 자동으로 접습니다(`▸ 그래프 펼치기`로 다시 열기).

창은 항상 내 PC에서 조작하고, **k6가 실행되는 곳만 고릅니다.**

| 실행 위치 | 동작 |
|---|---|
| **이 PC** | 내 PC의 k6를 실행 |
| **VM (SSH)** | VM에 SSH로 접속해 k6를 원격 실행. 실시간 지표는 SSH 포트포워딩으로 받아오고, 중지는 VM의 k6에 종료 신호를 보냅니다 |

**VM 사용 순서**
1. VM에 [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/)와 `python3`를 설치하고, 내 PC에서 **비밀번호 없이(키 인증)** 접속되는지 확인합니다 (`ssh ubuntu@10.0.0.5`).
2. 앱에서 `VM (SSH)` 선택 → 호스트(`user@host`), 키 파일, VM 경로를 입력 → `연결 확인`.
3. `프로젝트 업로드`로 이 폴더를 VM에 복사합니다 (`data/`, `results/`는 제외, 시나리오를 고치면 다시 업로드).
4. `테스트 계정 만들기`는 VM에서 실행되고, 결과 파일(`results/`)도 VM에 저장됩니다. 종료 시 요약 지표는 앱이 자동으로 가져옵니다.

**VM 사용 시 주의**
- 대상 URL은 **VM에서 접근 가능한 주소**여야 합니다 (예: 서버가 같은 VPC에 있으면 사설 IP). `localhost`는 VM 자신을 가리킵니다.
- SSH는 시스템의 `ssh` 명령을 그대로 씁니다(Windows 10+ 기본 포함). 비밀번호 입력은 지원하지 않으며, 처음 접속하는 호스트 키는 자동 등록(`accept-new`)됩니다.
- 시나리오 수치 범위·프리셋은 `scenarios.json`만 고치면 앱에 반영됩니다.
- 대상 URL이 로컬/내부 주소가 아니면 "내 서버입니다" 체크가 있어야 실행됩니다.
- 앱 설정은 `~/.jejari_loadtest.json`에 저장됩니다 (비밀번호·키 내용은 저장하지 않고, 키 파일 경로만 저장).

## 시나리오 10: Oracle을 쓰는 회원 인증 (실제 앱으로)

대기열(1·6·8·9)은 Redis만 쓰지만, 회원가입·로그인은 **Oracle**에 쓰고 읽습니다(가입 한 건이 `member`·`member_credential`·`identity_verification` 세 행을 한 트랜잭션으로 INSERT, 로그인은 조회 + Argon2id 검증). 이 시나리오는 그 경로를 실제 DB로 확인합니다.

| 단계 | 하는 일 | 합격 기준 |
|---|---|---|
| 1. 동시 가입 경합 | 같은 이메일로 N명이 동시에 가입 (라운드 반복) | 라운드마다 **정확히 1명만 201**, 나머지는 409 (`auth_race_winners{round:n} == 1`) |
| 2. 가입 처리량 | 서로 다른 사람이 초당 N건 가입 (본인인증 + 가입) | 실패 0건, p95 < 2초 |
| 3. 로그인 처리량 | 미리 만든 계정으로 초당 N건 로그인 | 실패 0건, p95 < 2초 |
| 4. 잠금 확인 | 한 계정에 틀린 비밀번호 반복 | 기준 횟수에서 423 잠금, 맞는 비밀번호도 막힘 |

**준비**

```bash
# 1) 인프라: oracle이 healthy이고 "jejari schema created" 로그가 있어야 합니다
docker compose up -d
docker compose ps
# 2) .env에 비밀값 3종이 있어야 인증 기능이 켜집니다 (비어 있으면 /api/v1/auth/**가 404)
#    JEJARI_HMAC_KEY, JEJARI_ENC_KEYS, JEJARI_PEPPER_KEYS  (.env.example 참고, 팀 공용 값)
# 3) 앱을 신뢰 프록시 옵션과 함께 실행 (저장소 루트에서)
./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1"
# 4) 실행
./run.sh 10                                    # 또는 ./run.sh auth -e SIGNUP_RATE=30 -e LOGIN_RATE=100
```

- **왜 신뢰 프록시 옵션이 필요한가**: 인증 API는 IP당 시도를 제한합니다(본인인증·가입 시간당 10회, 로그인 분당 20회 기본). 한 PC에서 쏘면 전부 같은 IP라 금방 429가 납니다. 시나리오는 요청마다 다른 IP를 `X-Forwarded-For`로 보내고, 앱은 이 헤더를 **신뢰 프록시 목록에 있는 연결 주소에서 온 것만** 믿습니다(위조로 제한을 우회하지 못하게 한 보안 설계). 그래서 테스트용 앱에만 로컬 주소를 신뢰 프록시로 넣습니다. **운영 설정에는 넣지 마세요.** 끄려면 `-e SPOOF_IP=no`.
- **사전 점검**: 시작 전에 가입 → 로그인을 한 번 끝까지 해 보고, 실패하면 원인(비밀값 누락 404, IP 제한 429, Oracle 연결 500, Redis 503 등)과 할 일을 알려 줍니다.
- **`auth_overloaded`(503)**: 비밀번호 해시 동시 처리 한도(`auth.max_concurrent_hash`, 기본 32)를 넘었다는 뜻입니다. 실패로 세지 않고 따로 보여 줍니다. 과부하가 시작되는 지점을 찾을 때 쓰세요.
- **`auth_rate_limited`(429)가 0이 아니면** 설정 문제입니다(신뢰 프록시 옵션 누락 등). 합격 기준에 들어 있습니다.
- **끝난 뒤**: Oracle에 `@loadtest.example.com` 계정이 남고, Redis에 `rate:*`·`login:fail:*`·`verify:*` 키가 남습니다(시간이 지나면 만료). 같은 `RUN_TAG`로 다시 돌리면 이메일이 겹쳐 409가 납니다(기본은 실행마다 다른 태그). 가입 정보를 확실히 비우려면 Oracle 볼륨을 초기화합니다(**로컬 Oracle 데이터가 모두 삭제**되고 스키마·시드는 다시 만들어집니다).

  ```bash
  docker compose rm -sf oracle && docker volume rm jejari_oracle-data && docker compose up -d oracle
  ```

- **Mock 서버로도 돌릴 수 있지만** 그때는 Oracle이 아니라 흉내입니다. 시나리오 로직 점검용이고, 성능 수치는 의미가 없습니다. `--bug dup-email`(동시 가입 중복), `--bug no-lockout`(잠금 안 걸림)으로 이 시나리오가 결함을 잡는지 볼 수 있습니다.
- 한계: 이 시나리오는 가입·로그인만 봅니다. 위험 점수 Oracle 조회(Redis 표시 키가 없을 때 진입마다 조회)는 대기열 진입 API가 실제 회원 연동(`MemberIdentityPort` 구현)을 쓰게 된 뒤에 걸 수 있습니다.

## 시나리오 11: 정상 사용자 전체 흐름 (실제 앱으로)

한 VU가 실제 회원 한 명입니다. setup에서 USERS명을 가입시킨 뒤, 각자 도착 시각을 흩뜨려(`SPREAD_SEC`) 한 번씩 예매를 끝까지 갑니다.

```
로그인(JWT) → 역 목록·운행 상세 → 대기열 진입·입장 대기 → 좌석 배치도(입장 토큰) → 빈 좌석 중 무작위 선점 → 결제(모의 PG) → 대기열 나가기
```

| 지표 | 뜻 |
|---|---|
| `journey_success` | 끝까지 마친 비율 (합격 95% 이상) |
| `journey_ms` | 로그인부터 결제까지 걸린 시간 (대기열 대기 포함) |
| `queue_wait_sec` | 진입 → 입장 |
| `seat_conflict` | 고른 좌석이 그 사이 잡혀 다른 좌석으로 다시 시도한 횟수 (오류가 아니라 경합 지표) |
| `journey_failed{stage}` | 어디서 멈췄는지 (`login`, `queue_RUN_NOT_ON_SALE`, `hold_LIMIT_PER_RUN`, `pay_…` 등) |
| `journey_sold_out` | 빈 좌석이 없음 (반복 실행으로 좌석이 팔린 경우) |

**준비와 실행**은 시나리오 10과 같습니다 (실제 앱을 신뢰 프록시 옵션으로). 운행은 시드의 판매 중 운행 9001(서울 → 부산)이 기본입니다.

```bash
./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1"
./run.sh 11                                  # 기본 30명, 20초에 걸쳐 도착
./run.sh journey -e USERS=100 -e SPREAD_SEC=30

# k6를 설치하지 않았다면 Docker로 (Windows는 Git Bash에서 MSYS_NO_PATHCONV=1을 앞에)
docker run --rm --add-host=host.docker.internal:host-gateway -v "$(pwd):/lt" -w /lt grafana/k6:1.1.0 \
  run scenarios/11_user_journey.js -e BASE_URL=http://host.docker.internal:8080 -e USERS=50
```

- 옵션: `FROM`·`TO`(역 코드, 기본 서울·부산), `PAY=no`(선점까지만), `CARD`(끝자리 0 = 거절, 8 = 결과 모름으로 실패 흐름 확인), `HOLD_RETRY`(기본 3), `MAX_WAIT_SEC`, `P95_MS`.
- Grafana **제자리 · 앱 개요**를 같이 열어 두면 단계별로 어느 쪽(스레드·DB 커넥션·Redis)이 먼저 차는지 볼 수 있습니다 (`docs/조영준/features/모니터링-대시보드.md`).
- 끝나면 회원과 결제된 예약(좌석 SOLD)이 Oracle에 남아 빈 좌석이 줄어듭니다. 기준 수치를 다시 잴 때는 Oracle 볼륨을 초기화하고 같은 `USERS`·`SPREAD_SEC`로 돌려야 비교가 됩니다.
- 한 사람은 한 번만 예매합니다 (열차당 매수 제한 때문에 같은 사람이 반복하면 `LIMIT_PER_RUN`).

## Mock 서버 (실제 서버가 없을 때 연습용)

앱의 `환경 설정 → Mock 서버`에서 `▶ 시작`을 누르면 `localhost:8080`에 가짜 서버가 뜨고 대상 URL이 자동으로 맞춰집니다(이 PC 실행 모드 전용).
명령줄로는 `python3 tools/mock_server.py` 입니다.

- 정상 모드에서는 시나리오 1~6, 8, 9, 10(인증은 메모리로 흉내)이 통과해야 합니다. 대기열은 실제 `queue` 앱과 같은 방식(진입 시각 + 위험 점수 패널티 순서, 입장 속도, 입장 토큰)으로 흉내 내고, 개발용 관리 API도 같은 경로로 제공합니다. 시나리오 7은 봇 계정을 `/api/admin/suspects`로 탐지하므로 `tools/eval_detection.py`까지 연습할 수 있습니다.
- **버그 모드**(`--bug dup-seat` 등)는 일부러 결함을 넣어 해당 시나리오가 "기준 미달"로 잡히는지 보여 줍니다. 결함을 끈 뒤 다시 실행하면 앱의 **결과 비교** 탭에서 전후 비교가 됩니다.
- 상태는 메모리에만 있습니다. 앱은 시나리오 시작 전마다 자동 초기화하고, 직접 쓸 때는 `http://localhost:8080/__mock`에서 초기화할 수 있습니다.
- **TPS·응답 시간은 mock 서버 자체의 값**이라 팀 서버 성능이 아닙니다(락 방식별 지연도 흉내). `가볍게`~`보통` 프리셋에서 쓰세요.

## 시나리오 ↔ 기획서 수치

| # | 파일 | 검증 수치 | 통과 기준(threshold) |
|---|---|---|---|
| 1 | `01_open_spike.js` | 대기열 처리량 / 동시 접속 (진입 → 입장 전체 흐름) | 실패율 5% 미만, queue_enter p95 2초 미만 |
| 2 | `02_same_seat_race.js` | **중복 예약 0건**, 락 방식별 TPS | `seat_success <= 1` |
| 3 | `03_payment_failure.js` | **좌석 복구율 100%** | `seat_not_recovered == 0` |
| 4 | `04_idempotency.js` | **중복 결제 0건** | `duplicate_payments == 0` |
| 5 | `05_cancel_waitlist.js` | 재배정 순서 보장 | `waitlist_order_violations == 0` |
| 6 | `06_token_bypass.js` | 토큰 없음·위조·남의 토큰·다른 운행·이탈 후 재사용 **차단율 100%**, 정상 토큰은 열림 | `bypass_allowed == 0`, `valid_rejected == 0` |
| 7 | `07_bot_simulation.js` | 탐지율 90%↑ / 오탐률 5%↓용 트래픽 | (평가는 `tools/eval_detection.py`) |
| 8 | `08_entry_burst.js` | **진입 API 처리 한계** (단계별로 초당 진입 수를 올림, 입장을 기다리지 않아 VU가 안 묶임) | 단계마다 진입 p95 500ms 미만(`P95_MS`), 실패율 1% 미만 |
| 9 | `09_penalty_order.js` | **순번 조정 정확도**: 위험 점수 높은 계정이 먼저 와도 정상 사용자 뒤에 입장 | `bot_admitted_after_all_normals == 100%`, `normal_admitted_before_bots == 100%` |
| 10 | `10_auth_oracle.js` | **회원가입·로그인 (Oracle)**: 같은 이메일 동시 가입은 1명만 성공, 가입·로그인 처리량·응답 시간, 비밀번호 5회 오류 잠금 | `auth_race_winners == 1`(라운드마다), `auth_signup_fail == 0`, `auth_login_fail == 0`, `auth_lockout_ok == 1`, `auth_rate_limited == 0`, 가입·로그인 p95 < 2초 |
| 11 | `11_user_journey.js` | **정상 사용자 전체 흐름 (실제 앱)**: 한 명이 예매를 끝내는 비율·시간, 단계별 응답 시간. 개선 전후 비교의 기준 | `journey_success >= 95%`, 로그인 p95 < 2초, 대기열 진입·좌석 배치도·선점 p95 < 1초(`P95_MS`), 결제 p95 < 2초 |

## 준비

```bash
# 1) k6 설치: https://grafana.com/docs/k6/latest/set-up/install-k6/
# 2) 테스트 계정 + 정답 라벨 생성 (data/users.csv, data/ground_truth.csv)
#    개발 모드의 회원 ID(memberId)가 함께 만들어진다 (기본 5000000 + 순번, k6의 MEMBER_BASE와 같아야 함)
python3 tools/gen_users.py --count 10000 --bot-ratio 0.05

# 3) (선택) 서버에 실제 가입까지 시키기 — 가입 API 필드는 --extra-json 으로 맞춤
python3 tools/gen_users.py --count 10000 \
  --register http://localhost:8080/api/members/signup \
  --extra-json '{"name":"테스트","phone":"01000000000"}'

# 4) (선택) 모니터링 스택
docker compose -f monitoring/docker-compose.monitor.yml up -d
```

## 실행

```bash
./run.sh 2                                  # 같은 좌석 동시 선점 (기본 500명)
./run.sh 2 -e RACE_VUS=1000 -e LOCK_STRATEGY=redis
PROM=1 ./run.sh 1 -e PEAK_RATE=2000         # 오픈 폭주 + Grafana로 지표 전송
./run.sh 7                                  # 정상 + 봇 트래픽

# 탐지율·오탐률 계산 (관리자 API의 위험 사용자 목록을 JSON/CSV로 저장해서 입력)
python3 tools/eval_detection.py --truth data/ground_truth.csv --suspects results/suspects.json
```

환경변수: `BASE_URL`, `RUN_ID`, `USERS_FILE`, `PEAK_RATE`, `RACE_VUS`, `HOLD_TTL_SEC`, `WAITERS`, `DUP_REQUESTS`, `LOCK_STRATEGY` 등.

## 반드시 맞춰야 하는 가정 (API 명세 확정 후 수정)

- **대기열·입장 토큰은 실제 구현에 맞췄고, 좌석 선점·결제·취소·취소표의 엔드포인트·필드·헤더는 임의로 정한 값**입니다. `config.js`의 `API`, `FIELDS`, `HEADERS`만 고치면 됩니다.
- **좌석 ID**: `LT-<시나리오>-<번호>` 형태(`seatIdFor`)로 요청합니다. 해당 좌석이 DB에 시드되어 있어야 하니,
  좌석 생성 스크립트를 따로 두거나 `lib/helpers.js`의 `seatIdFor`를 실제 ID 규칙에 맞게 바꾸세요.
  시나리오 2는 `-e TARGET_SEAT=<실제 좌석 ID>` 로 직접 지정할 수도 있습니다.
- **mock PG**: `X-Mock-PG: success | fail` 헤더로 결과를 지정할 수 있어야 시나리오 3·4·5가 의미 있습니다 (테스트 프로파일에서만 활성화하세요).
- **락 방식 전환**: 서버가 `X-Lock-Strategy` 헤더를 지원하지 않으면 프로파일/설정으로 서버를 재시작하며 같은 시나리오를 반복 실행하면 됩니다.
- **HMAC 가명처리**: 관리자 화면이 가명 ID만 보여주면 `--map pseudo_id,userId` CSV를 넘겨 정답지와 연결하세요.
- 라벨(`bot_*`)은 서버로 전송하지 않습니다. 서버가 블라인드로 판정해야 탐지율이 의미를 가집니다.

## 결과를 믿기 위한 체크리스트

1. k6 실행 머신과 서버를 분리하세요 (같은 PC면 CPU를 서로 뺏습니다).
2. `dropped_iterations`가 0에 가까운지 확인하세요 — 많으면 부하 생성기가 한계라는 뜻입니다.
3. 개선 전후 비교는 같은 시나리오·같은 데이터·같은 인프라에서 한 번에 하나만 바꾸세요.
4. 서버 쪽 지표(Hikari 커넥션, Redis, Kafka lag, Outbox 미발행 건수)를 Grafana에서 같이 보세요.

## 구조

```
config.js            API 경로·필드·헤더, 로컬 대상 안전장치
lib/auth.js          회원 인증 시나리오 공용 (가짜 신원·요청마다 다른 IP·사전 점검)
lib/helpers.js       인증(개발 모드/JWT)·대기열·선점·결제·취소·개발용 관리 API·사전 점검 헬퍼
scenarios/           시나리오 10개 (8·9는 대기열 진입 한계·순번 조정, 10은 Oracle을 쓰는 회원 인증)
tools/gen_users.py   계정·정답 라벨 생성기 (+ 선택적 회원가입)
tools/eval_detection.py  탐지율·오탐률 계산
tools/mock_server.py 연습용 가짜 서버 (실제 queue 앱과 같은 대기열 API + 좌석·결제·취소표, 정상 / 버그 주입 모드)
monitoring/          Prometheus + Grafana (k6 remote write 수신)
run.sh               시나리오 실행 래퍼 (CLI)
app/                 데스크톱 앱 (core.py 엔진·이력·비교 + jejari_desktop.py 화면)
scenarios.json       시나리오 수치 범위·프리셋 정의
app.sh / app.bat     데스크톱 앱 실행
```
