-- QUE-01 대기열 진입 (기능설계서 4.3). 중복 확인과 ZADD를 한 번에 원자적으로 처리한다.
-- KEYS[1] queue:{runId}   KEYS[2] queue:seq:{runId}   KEYS[3] queue:member:{memberId}   KEYS[4] queue:ci:{ciHash}
-- ARGV[1] runId  ARGV[2] memberId  ARGV[3] ciHash  ARGV[4] penaltyMs
--      ARGV[5] entryTtlMs  ARGV[6] 판매 마감 epoch ms (0이면 만료 없음)
-- 진입 시각은 앱이 아니라 이 스크립트 안에서 Redis TIME으로 읽는다. 그래야 번호표(seq) 순서와 시각 순서가
-- 항상 같고, 앱 서버가 여러 대여도 시계 차이로 순번이 뒤집히지 않는다.
-- 반환: {status, queueMember, initialRank, adjustedRank, nowMs}
--   status  0 새 진입 / 1 같은 운행에 이미 있음(기존 순번 그대로) / -1 다른 운행 대기 중 / -2 같은 CI의 다른 회원 대기 중
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local existing = redis.call('GET', KEYS[3])
if existing then
  local run, qm = string.match(existing, '^([^|]+)|([^|]+)')
  if run == ARGV[1] then
    return {1, qm, 0, 0, now}
  end
  return {-1, '', 0, 0, now}
end

-- 같은 CI의 다른 회원이 남아 있으면 거절 (만료된 멤버는 먼저 정리)
redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now)
local others = redis.call('ZRANGE', KEYS[4], 0, -1)
for _, m in ipairs(others) do
  if m ~= ARGV[2] then
    return {-2, '', 0, 0, now}
  end
end

-- 패널티가 없었다면 몇 번째였을지: 지금 이전 score를 가진 사람 수 + 1
local initial = redis.call('ZCOUNT', KEYS[1], '-inf', now) + 1
local seq = redis.call('INCR', KEYS[2])
local qm = string.format('%012d:%s', seq, ARGV[2])
redis.call('ZADD', KEYS[1], now + tonumber(ARGV[4]), qm)
-- value = {runId}|{queue member}|{ciHash}  (ciHash는 입장·이탈 때 queue:ci를 찾기 위한 덧붙임)
redis.call('SET', KEYS[3], ARGV[1] .. '|' .. qm .. '|' .. ARGV[3], 'PX', ARGV[5])
redis.call('ZADD', KEYS[4], now + tonumber(ARGV[5]), ARGV[2])
local closeAt = tonumber(ARGV[6])
if closeAt > 0 then
  redis.call('PEXPIREAT', KEYS[1], closeAt)
  redis.call('PEXPIREAT', KEYS[2], closeAt)
end
local adjusted = redis.call('ZRANK', KEYS[1], qm) + 1
return {0, qm, initial, adjusted, now}
