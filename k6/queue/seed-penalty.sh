#!/usr/bin/env bash
# penalty-order.js 준비: 봇 계정의 위험 점수를 Redis에 넣고, 입장 속도를 낮춰 대기가 쌓이게 한다.
# 개발용 회원의 CI 해시는 "dev-ci-{회원ID}"이므로 risk:CI:dev-ci-{회원ID} 키에 점수를 넣는다.
set -euo pipefail
# Redis 접속: REDIS_CONTAINER가 있으면 그 컨테이너 안의 redis-cli(비밀번호는 컨테이너 환경에서 자동 사용),
# 없으면 로컬 redis-cli (비밀번호가 있으면 REDISCLI_AUTH=<.env의 REDIS_PASSWORD> 를 먼저 설정)
if [ -n "${REDIS_CONTAINER:-}" ]; then
  REDIS="docker exec -i ${REDIS_CONTAINER} redis-cli -n ${REDIS_DB:-0}"
else
  REDIS="redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} -n ${REDIS_DB:-0}"
fi
BOTS=${BOTS:-20}
BOT_BASE=${BOT_BASE:-3000000}
BOT_SCORE=${BOT_SCORE:-80}
# 0이면 입장 처리가 멈춘다. 줄이 다 찬 뒤 run-penalty-order.sh가 올린다.
ADMIT_PER_SEC=${ADMIT_PER_SEC:-0}

for ((i = 0; i < BOTS; i++)); do
  $REDIS set "risk:CI:dev-ci-$((BOT_BASE + i))" "$BOT_SCORE" > /dev/null
done
$REDIS hset policy:cache queue.admit_per_sec "$ADMIT_PER_SEC" > /dev/null
echo "봇 ${BOTS}명에 위험 점수 ${BOT_SCORE} 설정, 입장 속도 ${ADMIT_PER_SEC}명/초"
