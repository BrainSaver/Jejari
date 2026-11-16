-- 매수 한도 1차 판정 (동시성 §2.1-2, §10.4, §10.6)
-- PER_RUN · PER_DAY · PER_HOLIDAY 를 한 번에 올리고, 하나라도 한도를 넘으면
-- 이번에 올린 것을 전부 되돌린다. Redis는 1차일 뿐이고 최종은 purchase_counter(§10.1)다.
--
-- 키가 없으면 purchase_counter 값으로 먼저 채운다(§10.4). SET NX라서 동시에 여러 요청이
-- 복원해도 한 값만 들어가고 나머지는 그 위에 더한다.
--
-- KEYS[1..k]            limit:{runId}:{ciHash} / limit:day:{yyyymmdd}:{ciHash}
--                       / limit:event:{saleEventId}:{ciHash}
-- ARGV[1]               n (요청 매수)
-- ARGV[2+(i-1)*3]       i번째 키의 한도 max (purchase_limit_policy, §10.6)
-- ARGV[3+(i-1)*3]       i번째 키의 복원값 (purchase_counter.seat_count, 없으면 '0')
-- ARGV[4+(i-1)*3]       i번째 키의 만료 (PXAT, epoch ms)
-- 반환                  {1}        통과
--                       {0, i}     i번째 키에서 초과 (앱이 LIMIT_PER_RUN /
--                                  LIMIT_PER_DAY / LIMIT_PER_HOLIDAY 를 가린다)

local n = tonumber(ARGV[1])
local done = {}

for i = 1, #KEYS do
  local base    = 2 + (i - 1) * 3
  local maxv    = tonumber(ARGV[base])
  local restore = ARGV[base + 1]
  local pxat    = tonumber(ARGV[base + 2])

  -- 없을 때만 DB 값으로 복원 (§10.4)
  redis.call('SET', KEYS[i], restore, 'NX', 'PXAT', pxat)

  local v = redis.call('INCRBY', KEYS[i], n)
  done[#done + 1] = i

  if v > maxv then
    for j = 1, #done do
      redis.call('DECRBY', KEYS[done[j]], n)
    end
    return {0, i}
  end
end

return {1}
