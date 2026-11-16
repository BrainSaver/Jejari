#!/usr/bin/env bash
# 개발용 Redis에서 대기열·위험 점수·정책 캐시 키를 지운다. 시나리오를 다시 돌리기 전에 실행한다.
# 주의: 개발 DB에서만 쓸 것. REDIS_DB로 지울 DB 번호를 정한다 (기본 0).
set -euo pipefail
REDIS="redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} -n ${REDIS_DB:-0}"
for pattern in 'queue:*' 'risk:*' 'policy:cache'; do
  $REDIS --scan --pattern "$pattern" | xargs -r -n 500 $REDIS del > /dev/null
done
echo "대기열·위험 점수·정책 캐시 키를 지웠습니다 (DB ${REDIS_DB:-0})"
