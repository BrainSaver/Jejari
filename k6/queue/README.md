# 대기열 k6 시나리오

대기열(QUE-01~05)의 성과 지표를 재는 시나리오입니다. 대상은 `queue/` 앱의 **개발 모드**(`X-Member-Id` 헤더로 로그인, 가짜 회원·운행)이고, 인프라 쪽 공용 시나리오(E 담당)와는 별개로 둡니다.

| 파일 | 재는 것 | 기획서 지표 |
|---|---|---|
| `flow.js` | 진입 → 번호표 폴링 → 입장 → 좌석 API → 이탈 전체 흐름, 응답 시간·입장까지 대기 시간 | 동시 접속 처리량 |
| `token-guard.js` | 토큰 없음·가짜·남의 토큰·다른 운행·이탈 후 토큰으로 좌석 API 호출 → 전부 403인가 | 토큰 차단율 100% |
| `entry-burst.js` | 진입 API만 초당 500·1000·2000·4000건… 단계별로 올려 어디서 느려지거나 실패하는지 (입장을 기다리지 않아 k6 VU가 안 묶임) | 동시 접속 처리량(한계) |
| `penalty-order.js` | 위험 점수 높은 계정이 먼저 와도 정상 사용자보다 뒤에 입장하는가 | 순번 조정 정확도 |

## 준비

```bash
# 1) Redis
redis-server --port 6379 --daemonize yes

# 2) 앱 (개발 모드, 운행 1001 판매 중)
cd queue
./gradlew bootRun --args='--jejari.dev-mode=true --jejari.queue.dev-on-sale-runs=1001'
```

k6 설치는 https://grafana.com/docs/k6/latest/set-up/install-k6/ 를 참고합니다.

## 실행

```bash
cd k6/queue

# 처리량: 초당 200명이 새로 줄을 서는 상황을 30초
./reset.sh && k6 run -e ARRIVAL_RATE=200 -e DURATION=30s flow.js

# 대기가 실제로 쌓이게 하려면 입장 속도를 낮춰서 (초당 20명)
redis-cli hset policy:cache queue.admit_per_sec 20

# 차단율
./reset.sh && k6 run -e VUS=50 -e ITERATIONS=500 token-guard.js

# 진입 API 한계: 단계별로 올려 가며 (끝난 뒤 ./reset.sh)
k6 run -e RATES=500,1000,2000,4000,8000 -e STEP_SEC=20 entry-burst.js

# 순번 조정 정확도 (초기화·위험 점수 설정·입장 시작 시점 조절까지 포함)
K6=$(which k6) ./run-penalty-order.sh
```

앱은 정책값(`queue.admit_per_sec` 등)을 최대 1초 들고 있어서(`jejari.queue.policy-cache-ms`), Redis에서 정책을 바꾼 뒤 1초쯤 지나야 반영됩니다. 정책을 바꾼 직후 바로 시나리오를 시작하면 이전 값이 섞일 수 있습니다.

시나리오를 다시 돌리기 전에는 `./reset.sh`로 대기열 키를 지웁니다 (개발 DB에서만).

## 읽는 법

- **flow**: `queue_admit_wait_ms`는 진입부터 입장까지 걸린 시간, `http_req_duration{name:queue_enter}`는 진입 API 응답 시간입니다. 임계값은 "크게 망가지지 않았는가"만 보는 느슨한 값이고, 목표 수치는 측정한 뒤 정합니다.
- **token-guard**: `guard_blocked`, `guard_allowed`가 둘 다 100%여야 통과입니다. 하나라도 어긋나면 임계값 실패로 종료 코드가 0이 아닙니다.
- **entry-burst**: 마지막 표에서 처음 기준(p95 500ms, 실패 1%, 목표의 95% 이상 진입)을 넘은 단계가 이 환경의 한계입니다. `못 보낸` 열이 0이 아니면 응답이 느려져 k6의 VU가 모자란 것이라, 서버 한계와 k6·PC 한계가 섞여 있습니다. `flow.js`는 입장을 기다리며 VU를 붙잡아서 이 한계를 재는 용도로는 맞지 않습니다.
- **penalty-order**: 마지막 요약의 "판정" 줄과 두 비율(목표 100%)을 봅니다. 봇을 먼저 줄 세운 뒤 정상 사용자 100명을 줄 세우고, 줄이 다 찬 다음에 입장을 시작합니다. 입장을 먼저 시작하면 줄에 봇밖에 없어 그대로 입장하는데, 이는 설계 의도입니다 (패널티는 "다른 사람보다 뒤"로 보낼 뿐 혼자 있는 사람을 붙잡아 두지 않음).

## 한계

- 앱이 로컬 한 대, Redis도 한 대입니다. 숫자는 이 환경에서 잰 값이라 배포 환경과 다릅니다.
- 좌석 API는 개발용 대용품(`DevSeatsController`)이라 좌석 선점 비용은 들어가지 않습니다. 좌석 동시성 부하는 B 파트 시나리오에서 잽니다.
- 개발 모드는 인증을 헤더로 대신하므로 로그인(Argon2) 부하는 포함하지 않습니다.
