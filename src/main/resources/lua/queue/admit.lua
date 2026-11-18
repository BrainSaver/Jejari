-- QUE-03 입장 처리. 한 운행의 맨 앞 N명을 꺼내 입장 토큰을 발급하고, 만료 정리·처리량 기록·활성 운행 정리까지 이 스크립트 한 번으로 한다
-- (운행마다 Redis 왕복을 1번으로 줄이기 위해).
-- KEYS[1] queue:{runId}   KEYS[2] queue:active:{runId}   KEYS[3] queue:runs   KEYS[4] queue:throughput:{runId}
-- ARGV[1] runId  ARGV[2] 초당 입장 수(정책)  ARGV[3] 토큰 유효 ms  ARGV[4] 초당 상한 적용(1) / 안 함(0)
-- ARGV[5..] 토큰 (이번에 입장시킬 수 있는 최대 인원만큼)   (시각은 Redis TIME)
-- 반환: 입장한 queue member 목록 (순서대로)
--
-- 앱이 여러 대여도 입장 속도가 정책값이 되도록, 상한을 켜면 "이번 1초에 이미 입장시킨 수"를 Redis(queue:adm:{runId}:{초})에서
-- 함께 세어 남은 만큼만 입장시킨다. 앱 두 대가 같은 초에 불러도 합쳐서 정책값을 넘지 않는다.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local perSec = tonumber(ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
-- 처리량은 값이 바뀔 때만 쓴다 (예상 대기 시간 계산용, 지금은 정책값 그대로)
if redis.call('GET', KEYS[4]) ~= ARGV[2] then
  redis.call('SET', KEYS[4], ARGV[2])
end

local out = {}
local want = #ARGV - 4
if want > perSec then
  want = perSec
end
local budgetKey = 'queue:adm:' .. ARGV[1] .. ':' .. math.floor(now / 1000)
if ARGV[4] == '1' then
  local left = perSec - (tonumber(redis.call('GET', budgetKey)) or 0)
  if want > left then
    want = left
  end
end

local expiry = now + tonumber(ARGV[3])
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
      redis.call('SET', 'queue:pass:' .. ARGV[1] .. ':' .. member, ARGV[4 + n], 'PX', ARGV[3])
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
if ARGV[4] == '1' and n > 0 then
  redis.call('INCRBY', budgetKey, n)
  redis.call('PEXPIRE', budgetKey, 3000)
end

-- 줄 선 사람도 입장 중인 사람도 없으면 활성 운행 목록에서 뺀다 (진입 때 enter.lua가 다시 넣는다. 둘 다 스크립트라 사이에 끼어들 수 없다)
if redis.call('ZCARD', KEYS[1]) == 0 and redis.call('ZCARD', KEYS[2]) == 0 then
  redis.call('SREM', KEYS[3], ARGV[1])
end
return out
