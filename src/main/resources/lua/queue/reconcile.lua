-- 활성 운행 목록(queue:runs) 맞추기. 판매 중인 운행 중 줄 선 사람이나 입장 중인 사람이 있는데 목록에 없는 운행을 넣는다
-- (목록 도입 전에 줄 선 사람, Redis 키 유실 등 대비. 앱이 뜰 때와 주기적으로 한 번에 처리해 Redis 왕복을 1번으로).
-- KEYS[1] queue:runs   ARGV[1..] runId
-- 반환: 새로 넣은 운행 수
local added = 0
for i = 1, #ARGV do
  local run = ARGV[i]
  if redis.call('EXISTS', 'queue:' .. run) == 1 or redis.call('EXISTS', 'queue:active:' .. run) == 1 then
    added = added + redis.call('SADD', KEYS[1], run)
  end
end
return added
