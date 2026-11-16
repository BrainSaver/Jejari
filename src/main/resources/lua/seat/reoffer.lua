-- 풀린 좌석을 대기자 몫으로 묶어 둔다 (동시성 §8.2, §9, §14.3)
-- 취소·만료로 풀린 좌석을 바로 열면 일반 사용자가 채 간다. REOFFER 값으로 바꿔
-- hold.lua를 막아 두고, §9가 대기자에게 먼저 제안한다.
-- 내 예약이 쥐고 있던 키(HOLD 또는 SOLD)만 바꾼다.
--
-- 커밋 후 보상은 빠른 경로와 outbox 보장 경로가 둘 다 실행되므로(§14.3)
-- **두 번 돌려도 결과가 같아야 한다.** 이미 REOFFER면 TTL을 다시 늘리지 않고 넘어간다.
--
-- KEYS[1..k] seat:hold:...
-- ARGV[1]    reservation_id
-- ARGV[2]    보류 시간 ms = policy(waitlist.reoffer_hold_sec) * 1000
-- 반환       대기자 몫으로 잡힌 키 수

local r    = ARGV[1]
local px   = tonumber(ARGV[2])
local sold = 'SOLD:' .. r
local newv = 'REOFFER:' .. r
local n = 0
for i = 1, #KEYS do
  local cur = redis.call('GET', KEYS[i])
  if cur == newv then
    n = n + 1                                   -- 이미 했음. TTL 손대지 않는다
  elseif cur == r or cur == sold then
    redis.call('SET', KEYS[i], newv, 'PX', px)
    n = n + 1
  end
end
return n
