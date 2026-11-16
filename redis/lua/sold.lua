-- 예약 확정 (동시성 §5.1-10)
-- DB가 이미 SOLD로 확정했으므로 Redis 값이 무엇이든 덮어쓴다.
-- 만료는 운행 출발 시각 — 그때까지 이 좌석은 아무도 못 잡는다.
--
-- KEYS[1..k] seat:hold:...
-- ARGV[1]    reservation_id
-- ARGV[2]    운행 출발 시각 (PXAT, epoch ms)
-- 반환       쓴 키 수 (= #KEYS)

local val  = 'SOLD:' .. ARGV[1]
local pxat = tonumber(ARGV[2])
for i = 1, #KEYS do
  redis.call('SET', KEYS[i], val, 'PXAT', pxat)
end
return #KEYS
