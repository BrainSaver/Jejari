# 개발용 Redis에서 대기열·위험 점수·정책 캐시 키를 지운다. 시나리오를 다시 돌리기 전에 실행한다.
# 주의: 개발 DB에서만 쓸 것. REDIS_DB 환경변수로 지울 DB 번호를 정한다 (기본 0).
#   .\reset.ps1
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\redis.ps1"

foreach ($pattern in @('queue:*', 'risk:*')) {
    $keys = @(Invoke-Redis @('--scan', '--pattern', $pattern) | Where-Object { $_ })
    for ($i = 0; $i -lt $keys.Count; $i += 200) {
        $last = [Math]::Min($i + 199, $keys.Count - 1)
        $chunk = @($keys[$i..$last])
        Invoke-Redis (@('del') + $chunk) | Out-Null
    }
}
Invoke-Redis @('del', 'policy:cache') | Out-Null
Write-Host "대기열·위험 점수·정책 캐시 키를 지웠습니다 (DB $($script:RedisDb))"
