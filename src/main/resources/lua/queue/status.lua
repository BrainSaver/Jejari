-- QUE-02 번호표 조회. 대기 여부·순번·토큰·입장 속도를 한 번에(원자적으로) 읽는다.
-- KEYS[1] queue:member:{memberId}   ARGV[1] runId  ARGV[2] memberId
-- 반환: {kind, queueMember, rank 또는 토큰 남은 ms, 토큰, 초당 입장 수}
--   kind 0 대기 중 아님 / 1 대기 중 / 2 입장함(토큰 있음)
local val = redis.call('GET', KEYS[1])
if not val then
  return {0}
end
local run, qm = string.match(val, '^([^|]+)|([^|]+)')
if run ~= ARGV[1] then
  return {0}
end
local tp = tonumber(redis.call('GET', 'queue:throughput:' .. run) or '0') or 0
local passKey = 'queue:pass:' .. run .. ':' .. ARGV[2]
local token = redis.call('GET', passKey)
if token then
  return {2, qm, redis.call('PTTL', passKey), token, tp}
end
local rank = redis.call('ZRANK', 'queue:' .. run, qm)
if rank then
  return {1, qm, rank + 1, '', tp}
end
return {0}
