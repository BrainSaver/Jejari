-- PG 결과를 모를 때 좌석 다시 잡기 (동시성 §6.2-2)
-- DB 마감이 지나 Redis 키가 TTL로 사라지면, 다른 사용자가 Redis 경쟁에서 이긴 뒤
-- §3 재선점에서 "결제 중이라 이어받을 수 없음"으로 계속 롤백된다. 그 헛도는 경쟁을 막는다.
-- 값이 내 것이거나 비어 있을 때만 쓴다. 남이 가져간 키는 건드리지 않는다.
--
-- KEYS[1..k] seat:hold:...
-- ARGV[1]    reservation_id
-- ARGV[2]    새 만료 (PXAT) = next_check_at + policy(payment.pg_recheck_sec)
-- 반환       다시 잡은 키 수

local val  = ARGV[1]
local pxat = tonumber(ARGV[2])
local n = 0
for i = 1, #KEYS do
  local cur = redis.call('GET', KEYS[i])
  if (not cur) or cur == val then
    redis.call('SET', KEYS[i], val, 'PXAT', pxat)
    n = n + 1
  end
end
return n
