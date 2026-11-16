-- 만료 연장 (동시성 §4.2-1 결제 시작, §11.2 릴레이 리스 갱신)
-- 값이 내 것일 때만 만료를 민다. **없는 키는 만들지 않는다** —
-- 이미 만료돼 남에게 넘어간 좌석을 되살리면 안 되기 때문이다.
--
-- KEYS[1..k] seat:hold:... 또는 relay:shard:{n}
-- ARGV[1]    기대하는 값 (reservation_id 또는 릴레이 인스턴스 ID)
-- ARGV[2]    새 만료 시각 (PXAT, epoch ms)
-- 반환       연장한 키 수. 좌석이면 K(§2.4)와 같아야 정상

local val  = ARGV[1]
local pxat = tonumber(ARGV[2])
local n = 0
for i = 1, #KEYS do
  if redis.call('GET', KEYS[i]) == val then
    redis.call('PEXPIREAT', KEYS[i], pxat)
    n = n + 1
  end
end
return n
