-- 단체 좌석 잡기 (concurrency.md §18.3-3, §18.3a-2 "스크립트 하나")
-- 할당석 키와 일반 좌석 키를 한 번에 잡는다. 전부 검사한 뒤 전부 쓰거나, 아무것도 쓰지 않는다 (hold.lua와 같은 "전부 아니면 전무").
--
-- KEYS[1..q]     할당석 seat:hold:{runId}:{seatId}:{segNo}  → 값이 'GROUP'이어야 함
-- KEYS[q+1..k]   일반 좌석 키                              → 비어 있어야 함 (REOFFER:R 등 남의 값이면 실패, G5c)
-- ARGV[1]        reservation_id (넣을 값)
-- ARGV[2]        만료 시각 PXAT (epoch ms) = 결제 마감 + reservation.hold_release_grace_sec (§0.2 예외)
-- ARGV[3]        q (할당석 키 개수)
-- 반환           1 전부 잡음 / 0 실패 (아무것도 바꾸지 않음)
--
-- 같은 값(ARGV[1])이 이미 있으면 통과한다 (같은 예약의 재시도).

local val  = ARGV[1]
local pxat = tonumber(ARGV[2])
local q    = tonumber(ARGV[3])

for i = 1, #KEYS do
  local cur = redis.call('GET', KEYS[i])
  if cur ~= val then
    if i <= q then
      if cur ~= 'GROUP' then
        return 0
      end
    elseif cur then
      return 0
    end
  end
end

for i = 1, #KEYS do
  redis.call('SET', KEYS[i], val, 'PXAT', pxat)
end
return 1
