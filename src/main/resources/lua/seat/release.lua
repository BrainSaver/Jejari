-- 좌석 키 해제 (동시성 §2.3, §9.8, §14.3)
-- 내 예약이 쥐고 있는 키만 지운다(compare-and-delete). 남이 이미 가져간 키는 건드리지 않는다.
--
-- KEYS[1..k] seat:hold:... (또는 idem 키)
-- ARGV[1]    reservation_id — 이 값인 키만 지운다
-- 반환       지운 키 수

local val = ARGV[1]
local n = 0
for i = 1, #KEYS do
  if redis.call('GET', KEYS[i]) == val then
    redis.call('DEL', KEYS[i])
    n = n + 1
  end
end
return n
