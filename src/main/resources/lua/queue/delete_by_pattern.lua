-- 개발 모드 전용(DevAdminController): 패턴에 맞는 키를 SCAN으로 훑으며 UNLINK한다. 24만 개에 약 0.4초.
-- ARGV[1] 패턴 (예: queue:*)   반환: 지운 키 수
local c = '0'
local n = 0
repeat
  local r = redis.call('SCAN', c, 'MATCH', ARGV[1], 'COUNT', 1000)
  c = r[1]
  for _, k in ipairs(r[2]) do
    redis.call('UNLINK', k)
    n = n + 1
  end
until c == '0'
return n
