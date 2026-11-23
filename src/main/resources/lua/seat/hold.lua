-- 좌석 선점 (동시성 §2.1-4, §9.6)
-- 예약할 모든 구간 키를 한꺼번에 잡는다. 하나라도 남이 쥐고 있으면 전부 실패한다.
-- "전부 아니면 전무"라야 일부 좌석만 잡힌 반쪽 예약이 생기지 않는다.
--
-- KEYS[1..k] seat:hold:{runId}:{seatId}:{segNo}
-- ARGV[1]    reservation_id (키에 넣을 값)
-- ARGV[2]    만료 시각 (PXAT, epoch ms) = reservation.pay_deadline_at + reservation.hold_release_grace_sec (§0.2 예외)
--            마감 뒤 유예 동안 키가 남아 일반 사용자를 막고, 만료 처리가 REOFFER로 바꿔 대기자에게 넘긴다
-- ARGV[3]    인계 값. 취소표 제안(§9)에서 'REOFFER:{이전예약}' 을 넘기면
--            그 값인 키는 비어 있는 것으로 보고 덮어쓴다. 평소에는 '' 를 넘긴다.
-- 반환       1 전부 잡음 / 0 실패 (아무것도 바꾸지 않음)

local val      = ARGV[1]
local pxat     = tonumber(ARGV[2])
local takeover = ARGV[3]

-- 1단계: 전부 검사. 하나라도 걸리면 아무것도 쓰지 않고 끝낸다.
for i = 1, #KEYS do
  local cur = redis.call('GET', KEYS[i])
  if cur then
    local mine     = (cur == val)                               -- 같은 예약의 재시도 (멱등)
    local handover = (takeover ~= '' and cur == takeover)       -- 대기자에게 넘기는 좌석
    if not (mine or handover) then
      return 0
    end
  end
end

-- 2단계: 전부 쓴다. Lua 안이라 원자적이다.
for i = 1, #KEYS do
  redis.call('SET', KEYS[i], val, 'PXAT', pxat)
end
return 1
