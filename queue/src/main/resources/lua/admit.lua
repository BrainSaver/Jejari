-- QUE-03 입장 처리. 맨 앞 N명을 꺼내 입장 토큰을 발급한다.
-- KEYS[1] queue:{runId}   KEYS[2] queue:active:{runId}
-- ARGV[1] runId  ARGV[2] 입장 인원 N  ARGV[3] nowMs  ARGV[4] 토큰 유효 ms  ARGV[5..] 토큰 N개
-- 반환: 입장한 queue member 목록 (순서대로)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[3])
local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[2])
local out = {}
local expiry = tonumber(ARGV[3]) + tonumber(ARGV[4])
local n = 0
for idx = 1, #popped, 2 do
  n = n + 1
  local qm = popped[idx]
  local member = string.match(qm, '^%d+:(.+)$')
  redis.call('SET', 'queue:pass:' .. ARGV[1] .. ':' .. member, ARGV[4 + n], 'PX', ARGV[4])
  redis.call('ZADD', KEYS[2], expiry, member)
  -- 입장한 회원은 토큰이 살아 있는 동안 계속 "대기 중"으로 센다 (회원당·CI당 1개)
  local mkey = 'queue:member:' .. member
  local val = redis.call('GET', mkey)
  if val then
    redis.call('PEXPIRE', mkey, ARGV[4])
    local ci = string.match(val, '^[^|]+|[^|]+|(.+)$')
    if ci then
      redis.call('ZADD', 'queue:ci:' .. ci, expiry, member)
    end
  end
  out[#out + 1] = qm
end
return out
