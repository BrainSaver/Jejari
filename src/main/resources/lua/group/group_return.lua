-- 단체 좌석 되돌리기 (concurrency.md §18.3-5). DB 트랜잭션이 롤백됐을 때 group_claim.lua를 되돌린다.
-- 값이 내 예약(ARGV[1])인 키만 바꾼다. 남이 이미 가져간 키는 건드리지 않는다. 두 번 불러도 결과가 같다.
--
-- KEYS[1..q]     할당석 키   → 'GROUP' PXAT 반납 시각으로 되돌림 (반납 시각이 이미 지났으면 지움)
-- KEYS[q+1..k]   일반 좌석 키 → 지움
-- ARGV[1]        reservation_id
-- ARGV[2]        반납 시각 PXAT (epoch ms) = 출발 − group.quota_release_days
-- ARGV[3]        q (할당석 키 개수)
-- 반환           되돌린 키 수

local val = ARGV[1]
local rel = tonumber(ARGV[2])
local q   = tonumber(ARGV[3])
local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local n = 0
for i = 1, #KEYS do
  if redis.call('GET', KEYS[i]) == val then
    if i <= q and rel > now then
      redis.call('SET', KEYS[i], 'GROUP', 'PXAT', rel)
    else
      redis.call('DEL', KEYS[i])
    end
    n = n + 1
  end
end
return n
