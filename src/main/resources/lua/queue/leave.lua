-- QUE-05 대기열 이탈. 대기 중이든 입장했든 모든 흔적을 지우고 토큰을 무효화한다.
-- KEYS[1] queue:member:{memberId}   ARGV[1] runId  ARGV[2] memberId
-- 반환: {status, wasWaiting}   status 1 이탈 처리 / 0 대기 중 아님 / -1 다른 운행에 대기 중
local val = redis.call('GET', KEYS[1])
if not val then
  return {0, 0}
end
local run, qm, ci = string.match(val, '^([^|]+)|([^|]+)|?(.*)$')
if run ~= ARGV[1] then
  return {-1, 0}
end
local waiting = redis.call('ZREM', 'queue:' .. run, qm)
redis.call('ZREM', 'queue:active:' .. run, ARGV[2])
redis.call('DEL', 'queue:pass:' .. run .. ':' .. ARGV[2])
-- 연장 표시도 지운다. 남겨 두면 다시 줄 서서 새로 입장했을 때 '이미 연장함'으로 막힌다
redis.call('DEL', 'queue:passext:' .. run .. ':' .. ARGV[2])
redis.call('DEL', KEYS[1])
if ci ~= '' then
  redis.call('ZREM', 'queue:ci:' .. ci, ARGV[2])
end
return {1, waiting}
