-- 입장 토큰 연장 (회원당 운행마다 1회). 남은 시간에 ARGV[3] ms를 더한다.
-- KEYS[1] queue:pass:{runId}:{memberId}   KEYS[2] queue:passext:{runId}:{memberId}
-- KEYS[3] queue:active:{runId}            KEYS[4] queue:member:{memberId}
-- ARGV[1] memberId  ARGV[2] 연장 ms  (시각은 Redis TIME)
-- 반환: {1, 새 남은 ms} 연장함 / {-1} 토큰 없음(만료·미입장) / {-2} 이미 한 번 연장함
local ttl = redis.call('PTTL', KEYS[1])
if ttl <= 0 then
  return {-1}
end
if redis.call('EXISTS', KEYS[2]) == 1 then
  return {-2}
end
local add = tonumber(ARGV[2])
local newTtl = ttl + add
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local expiry = now + newTtl
redis.call('PEXPIRE', KEYS[1], newTtl)
-- 연장 표시는 토큰과 같이 사라진다. 토큰이 끝나고 다시 줄을 서면 새 차례라 다시 연장할 수 있다
redis.call('SET', KEYS[2], '1', 'PX', newTtl)
-- admit.lua와 같게: 입장자 목록·회원 대기 기록·CI 대기 기록의 만료도 함께 늦춘다
redis.call('ZADD', KEYS[3], expiry, ARGV[1])
local val = redis.call('GET', KEYS[4])
if val then
  redis.call('PEXPIRE', KEYS[4], newTtl)
  local ci = string.match(val, '^[^|]+|[^|]+|(.+)$')
  if ci then
    redis.call('ZADD', 'queue:ci:' .. ci, expiry, ARGV[1])
  end
end
return {1, newTtl}
