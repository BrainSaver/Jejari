# 개발용 Redis에서 대기열·위험 점수·정책 캐시 키를 지운다. 시나리오를 다시 돌리기 전에 실행한다.
# 주의: 개발 DB에서만 쓸 것. REDIS_DB 환경변수로 지울 DB 번호를 정한다 (기본 0).
#   .\reset.ps1
#
# entry-burst.js를 한 번 돌리면 키가 수십만 개 남는다. 키를 하나씩(또는 200개씩) docker exec로 지우면 Windows에서
# 10분 넘게 걸리므로, Redis 안에서 SCAN으로 훑으며 UNLINK하는 스크립트를 한 번만 실행한다 (24만 개에 약 0.4초).
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\redis.ps1"

# 작은따옴표만 쓴다 (Windows PowerShell 5.1이 네이티브 명령에 큰따옴표를 넘길 때 깨지는 것을 피하려고)
$deleteByPattern = "local c='0' local n=0 repeat local r=redis.call('SCAN',c,'MATCH',ARGV[1],'COUNT',1000) c=r[1] for _,k in ipairs(r[2]) do redis.call('UNLINK',k) n=n+1 end until c=='0' return n"

foreach ($pattern in @('queue:*', 'risk:*')) {
    $n = Invoke-Redis @('eval', $deleteByPattern, '0', $pattern)
    Write-Host "$pattern : $n 개 삭제"
}
Invoke-Redis @('del', 'policy:cache') | Out-Null
Write-Host "대기열·위험 점수·정책 캐시 키를 지웠습니다 (DB $($script:RedisDb))"
