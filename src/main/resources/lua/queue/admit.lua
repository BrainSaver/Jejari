-- QUE-03 입장 처리. 맨 앞 N명을 꺼내 입장 토큰을 발급한다.
-- KEYS[1] queue:{runId}   KEYS[2] queue:active:{runId}
-- ARGV[1] runId  ARGV[2] 입장 인원 N  ARGV[3] 토큰 유효 ms  ARGV[4..] 토큰 N개  (시각은 Redis TIME)
-- 반환: 입장한 queue member 목록 (순서대로)
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
local out = {}
local expiry = now + tonumber(ARGV[3])
local want = tonumber(ARGV[2])
local n = 0
-- 대기 기록(queue:member)이 만료됐거나 다시 줄을 선 사람의 옛 항목(유령)은 입장시키지 않고 건너뛴다.
-- 건너뛴 자리는 다음 사람으로 채운다.
while n < want do
  local popped = redis.call('ZPOPMIN', KEYS[1], want - n)
  if #popped == 0 then
    break
  end
  for idx = 1, #popped, 2 do
    local qm = popped[idx]
    local member = string.match(qm, '^%d+:(.+)$')
    local mkey = 'queue:member:' .. member
    local val = redis.call('GET', mkey)
    local cur = val and string.match(val, '^[^|]+|([^|]+)')
    if member and cur == qm then
      n = n + 1
      redis.call('SET', 'queue:pass:' .. ARGV[1] .. ':' .. member, ARGV[3 + n], 'PX', ARGV[3])
      redis.call('ZADD', KEYS[2], expiry, member)
      -- 입장한 회원은 토큰이 살아 있는 동안 계속 "대기 중"으로 센다 (회원당·CI당 1개)
      redis.call('PEXPIRE', mkey, ARGV[3])
      local ci = string.match(val, '^[^|]+|[^|]+|(.+)$')
      if ci then
        redis.call('ZADD', 'queue:ci:' .. ci, expiry, member)
      end
      out[#out + 1] = qm
    end
  end
end
return out
