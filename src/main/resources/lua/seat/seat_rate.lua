-- 좌석 조회·선점 속도 제한: 토큰 버킷 + 계속 부딪히는 계정은 잠깐 쉬게 하기
--
-- (회원, 운행) 하나당 해시 하나. 새로고침을 자주 눌러도 처음엔 burst번까지 그냥 지나가고(물통),
-- 그 뒤로는 초당 refill번씩 채워진다. 물이 바닥난 채로 거절을 abuse_threshold번 넘게 받으면(봇처럼 한도에 붙어서 두드림)
-- cooldown 동안 아예 막는다. 사람은 거의 한도에 닿지 않아 이 잠금까지 가지 않는다.
--
-- KEYS[1]  rate:seatmap:{runId}:{memberId} 또는 rate:seathold:{runId}:{memberId}
-- ARGV[1]  지금 (epoch ms, 앱 시계)
-- ARGV[2]  burst 물통 크기 (회)
-- ARGV[3]  refill 초당 채워지는 수 (회/초)
-- ARGV[4]  abuse_threshold 이 횟수만큼 거절당하면 잠금
-- ARGV[5]  abuse_window_ms 거절을 세는 시간 창
-- ARGV[6]  cooldown_ms 잠금 시간
-- 반환     {허용(1/0), 몇 ms 뒤에 다시(허용이면 0), 잠금 중인지(1/0)}

local now       = tonumber(ARGV[1])
local burst     = tonumber(ARGV[2])
local refill    = tonumber(ARGV[3]) / 1000          -- 회/ms
local threshold = tonumber(ARGV[4])
local window    = tonumber(ARGV[5])
local cooldown  = tonumber(ARGV[6])
-- 키가 쌓이지 않게: 가장 오래 쓰는 값(물통이 다시 차는 시간·거절 창·잠금) 뒤에 사라진다
local ttl = math.max(cooldown, window, math.ceil(burst / refill)) + 1000

local d = redis.call('HMGET', KEYS[1], 'tok', 'ts', 'strk', 'strk_ts', 'lock')
local lock = tonumber(d[5]) or 0
if lock > now then
  return {0, lock - now, 1}
end

local tok = tonumber(d[1])
local ts  = tonumber(d[2])
if tok == nil or ts == nil then
  tok = burst
  ts  = now
end
tok = math.min(burst, tok + math.max(0, now - ts) * refill)

if tok >= 1 then
  redis.call('HSET', KEYS[1], 'tok', tostring(tok - 1), 'ts', now)
  redis.call('PEXPIRE', KEYS[1], ttl)
  return {1, 0, 0}
end

-- 물이 바닥났다: 거절을 센다
local strk    = tonumber(d[3]) or 0
local strk_ts = tonumber(d[4]) or now
if now - strk_ts > window then
  strk = 0
  strk_ts = now
end
strk = strk + 1

if strk >= threshold then
  redis.call('HSET', KEYS[1], 'tok', tostring(tok), 'ts', now, 'strk', 0, 'strk_ts', now, 'lock', now + cooldown)
  redis.call('PEXPIRE', KEYS[1], ttl)
  return {0, cooldown, 1}
end

redis.call('HSET', KEYS[1], 'tok', tostring(tok), 'ts', now, 'strk', strk, 'strk_ts', strk_ts)
redis.call('PEXPIRE', KEYS[1], ttl)
return {0, math.ceil((1 - tok) / refill), 0}
