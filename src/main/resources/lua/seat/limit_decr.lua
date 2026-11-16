-- 매수 한도 되돌리기 (동시성 §2.3, §9.1-1, §14.2, §14.3)
-- **예약 하나에 딱 한 번만** 감소시킨다. 커밋 후 보상은 빠른 경로(앱)와
-- 보장 경로(outbox seat-release 소비)가 둘 다 실행되는데, 두 번 깎이면
-- limit:* 가 DB보다 작아져 한도를 넘겨 팔게 된다.
-- 표시 키를 SET NX로 선점한 쪽만 실제로 깎는다.
--
-- KEYS[1]     limit:dec:{reservationId}  (한 번만 했다는 표시)
-- KEYS[2..k]  limit:{runId}:{ciHash} / limit:day:... / limit:event:...
-- ARGV[1]     n (되돌릴 매수)
-- ARGV[2]     표시 키 만료 (PXAT) — 운행 출발·행사 종료 중 늦은 쪽
-- 반환        1 이번에 감소함 / 0 이미 처리된 예약이라 아무것도 안 함

local n         = tonumber(ARGV[1])
local markPxat  = tonumber(ARGV[2])

if not redis.call('SET', KEYS[1], '1', 'NX', 'PXAT', markPxat) then
  return 0
end

for i = 2, #KEYS do
  -- 없는 키에 DECRBY 하면 만료 없는 음수 키가 새로 생긴다. 있는 키만 건드린다.
  if redis.call('EXISTS', KEYS[i]) == 1 then
    local v = redis.call('DECRBY', KEYS[i], n)
    if v < 0 then
      redis.call('SET', KEYS[i], 0, 'KEEPTTL')   -- CHECK(seat_count >= 0) 과 같은 방어
    end
  end
end
return 1
