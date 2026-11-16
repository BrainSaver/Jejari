# 순번 조정 정확도 시나리오 실행: 초기화 → 봇 위험 점수 설정(입장 정지) → 줄 서기 → 입장 시작 → 판정.
#   .\run-penalty-order.ps1
#   .\run-penalty-order.ps1 -Bots 20 -Normals 100 -AdmitPerSec 10
# k6 위치가 PATH에 없으면: .\run-penalty-order.ps1 -K6 'C:\경로\k6.exe'
param(
    [int]$Bots = 20,
    [int]$Normals = 100,
    [int]$BotScore = 80,
    [int]$AdmitPerSec = 10,     # 입장 시작 후 초당 입장 인원
    [int]$ReleaseAfter = 6,     # 줄이 다 찰 때까지 기다리는 시간(초)
    [int]$PolicyWait = 2,       # 앱이 정책값을 최대 1초 들고 있어서, 입장 정지가 반영될 때까지 기다리는 시간(초)
    [string]$K6 = $(if ($env:K6) { $env:K6 } else { 'k6' })
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\lib\redis.ps1"

& "$PSScriptRoot\reset.ps1"
& "$PSScriptRoot\seed-penalty.ps1" -Bots $Bots -BotScore $BotScore -AdmitPerSec 0
Start-Sleep -Seconds $PolicyWait

$k6Args = @('run', '-q', '-e', "BOTS=$Bots", '-e', "NORMALS=$Normals", 'penalty-order.js')
$proc = Start-Process -FilePath $K6 -ArgumentList $k6Args -NoNewWindow -PassThru
$null = $proc.Handle    # 종료 코드를 읽으려면 핸들을 먼저 잡아 둬야 한다 (PowerShell의 알려진 동작)

Start-Sleep -Seconds $ReleaseAfter
Invoke-Redis @('hset', 'policy:cache', 'queue.admit_per_sec', "$AdmitPerSec") | Out-Null
Write-Host "입장 시작: 초당 $AdmitPerSec 명"

$proc.WaitForExit()
exit $proc.ExitCode
