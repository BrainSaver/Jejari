#!/usr/bin/env bash
# 개발용 Redis에서 대기열·위험 점수·정책 캐시 키를 지운다. 시나리오를 다시 돌리기 전에 실행한다.
# 주의: 개발 DB에서만 쓸 것. REDIS_DB로 지울 DB 번호를 정한다 (기본 0).
set -euo pipefail
REDIS="redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} -n ${REDIS_DB:-0}"
# 키가 수십만 개일 수 있어 Redis 안에서 SCAN + UNLINK으로 한 번에 지운다 (24만 개에 약 0.4초)
DELETE_BY_PATTERN="local c='0' local n=0 repeat local r=redis.call('SCAN',c,'MATCH',ARGV[1],'COUNT',1000) c=r[1] for _,k in ipairs(r[2]) do redis.call('UNLINK',k) n=n+1 end until c=='0' return n"
for pattern in 'queue:*' 'risk:*'; do
  $REDIS eval "$DELETE_BY_PATTERN" 0 "$pattern" > /dev/null
done
$REDIS del policy:cache > /dev/null
echo "대기열·위험 점수·정책 캐시 키를 지웠습니다 (DB ${REDIS_DB:-0})"
