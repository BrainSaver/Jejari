# penalty-order.js 준비: 봇 계정의 위험 점수를 Redis에 넣고, 입장 속도를 정한다.
# 개발용 회원의 CI 해시는 "dev-ci-{회원ID}"이므로 risk:CI:dev-ci-{회원ID} 키에 점수를 넣는다.
#   .\seed-penalty.ps1 -Bots 20 -BotScore 80 -AdmitPerSec 0
# AdmitPerSec 0은 입장 처리를 멈춘다 (줄이 다 찬 뒤 run-penalty-order.ps1이 올린다).
param(
    [int]$Bots = 20,
    [int]$BotBase = 3000000,
    [int]$BotScore = 80,
    [int]$AdmitPerSec = 0
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\redis.ps1"

for ($i = 0; $i -lt $Bots; $i++) {
    $id = $BotBase + $i
    Invoke-Redis @('set', "risk:CI:dev-ci-$id", "$BotScore") | Out-Null
}
Invoke-Redis @('hset', 'policy:cache', 'queue.admit_per_sec', "$AdmitPerSec") | Out-Null
Write-Host "봇 $Bots 명에 위험 점수 $BotScore 설정, 입장 속도 $AdmitPerSec 명/초"
