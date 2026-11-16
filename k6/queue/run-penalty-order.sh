#!/usr/bin/env bash
# 순번 조정 정확도 시나리오 실행: 초기화 → 봇 위험 점수 설정 → (입장 정지 상태에서) 줄 서기 → 입장 시작 → 판정.
#   K6=/path/to/k6 BOTS=20 NORMALS=100 ./run-penalty-order.sh
set -euo pipefail
cd "$(dirname "$0")"
# Redis 접속: REDIS_CONTAINER가 있으면 그 컨테이너 안의 redis-cli(비밀번호는 컨테이너 환경에서 자동 사용),
# 없으면 로컬 redis-cli (비밀번호가 있으면 REDISCLI_AUTH=<.env의 REDIS_PASSWORD> 를 먼저 설정)
if [ -n "${REDIS_CONTAINER:-}" ]; then
  REDIS="docker exec -i ${REDIS_CONTAINER} redis-cli -n ${REDIS_DB:-0}"
else
  REDIS="redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} -n ${REDIS_DB:-0}"
fi
K6=${K6:-k6}
RELEASE_AFTER=${RELEASE_AFTER:-6}      # 줄이 다 찰 때까지 기다리는 시간(초)
ADMIT_PER_SEC=${ADMIT_PER_SEC:-10}     # 입장 시작 후 초당 입장 인원

./reset.sh
ADMIT_PER_SEC=0 ./seed-penalty.sh
# 앱은 정책값을 최대 jejari.queue.policy-cache-ms(기본 1초) 동안 들고 있다. 바뀐 값(입장 정지)이 반영될 때까지 기다린다.
sleep "${POLICY_WAIT:-2}"
"$K6" run -q "$@" penalty-order.js &
K6_PID=$!
sleep "$RELEASE_AFTER"
$REDIS hset policy:cache queue.admit_per_sec "$ADMIT_PER_SEC" > /dev/null
echo "입장 시작: 초당 ${ADMIT_PER_SEC}명"
wait "$K6_PID"
