# Redis 명령 실행 도우미. 다른 .ps1에서 `. "$PSScriptRoot\lib\redis.ps1"`로 불러 쓴다.
#  - Docker로 띄운 Redis(컨테이너 이름 jejari-redis)가 실행 중이면 `docker exec`로, 아니면 로컬 redis-cli로 실행한다.
#  - 환경변수: REDIS_CONTAINER(컨테이너 이름), REDIS_DB(DB 번호, 기본 0), REDIS_HOST, REDIS_PORT(로컬 redis-cli일 때)

$script:RedisDb = if ($env:REDIS_DB) { $env:REDIS_DB } else { '0' }
$script:RedisContainer = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'jejari-redis' }
$script:RedisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { 'localhost' }
$script:RedisPort = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { '6379' }

# 컨테이너 확인은 처음 한 번만 한다. 명령마다 `docker ps`를 부르면 Windows에서 호출당 0.5초 안팎이 더 든다.
$script:UseContainer = $null

function Test-RedisContainer {
    if ($null -eq $script:UseContainer) {
        $script:UseContainer = $false
        if (Get-Command docker -ErrorAction SilentlyContinue) {
            $filter = 'name=^{0}$' -f $script:RedisContainer
            $id = docker ps -q --filter $filter 2>$null
            $script:UseContainer = [bool]$id
        }
    }
    return $script:UseContainer
}

# 사용: Invoke-Redis @('hset', 'policy:cache', 'queue.admit_per_sec', '10')
function Invoke-Redis {
    param([string[]]$RedisArgs)
    # Windows PowerShell 5.1은 네이티브 명령이 stderr에 쓰기만 해도 'Stop' 설정에서 오류로 중단하므로 이 함수 안에서는 끈다
    $ErrorActionPreference = 'Continue'
    if (Test-RedisContainer) {
        & docker exec $script:RedisContainer redis-cli -n $script:RedisDb @RedisArgs
    }
    elseif (Get-Command redis-cli -ErrorAction SilentlyContinue) {
        & redis-cli -h $script:RedisHost -p $script:RedisPort -n $script:RedisDb @RedisArgs
    }
    else {
        throw "Redis에 접근할 수 없습니다. Docker 컨테이너($($script:RedisContainer))를 켜거나(docker start $($script:RedisContainer)) redis-cli를 설치하세요."
    }
}
