#!/usr/bin/env bash
# 앱 배포·재배포 (서버에서). main을 받아 빌드하고 앱 2대를 한 대씩 재시작합니다.
#   cd ~/Jejari && bash deploy/deploy.sh            (BRANCH=main 기본)
#
# 한 대씩 재시작하는 이유: Caddy가 살아 있는 쪽으로 보내 배포 중에도 요청이 끊기지 않게
set -euo pipefail
cd "$(dirname "$0")/.."
BRANCH="${BRANCH:-main}"

health() { curl -fs "http://127.0.0.1:$1/actuator/health" | grep -q '"status":"UP"'; }

# 인프라가 떠 있는지 (Oracle healthy = 앱 계정으로 FREEPDB1 접속까지 확인됨)
[[ "$(docker inspect -f '{{.State.Health.Status}}' jejari-oracle-1 2>/dev/null)" == healthy ]] \
  || { echo "Oracle이 healthy가 아닙니다. docker compose -f compose.yaml -f deploy/compose.server.yaml up -d 후 기다리세요."; exit 1; }

git fetch -q origin "$BRANCH"
git checkout -q "$BRANCH"
git merge -q --ff-only "origin/$BRANCH"
echo "빌드: $(git log --oneline -1)"
LC_ALL=C.UTF-8 TZ=Asia/Seoul ./gradlew -q bootJar   # LC_ALL: 한글 파일 이름 클래스

JAR=$(ls -t build/libs/*.jar | grep -v -- '-plain' | head -1)
mkdir -p "$HOME/jejari-app"
cp "$JAR" "$HOME/jejari-app/jejari.jar.new"
mv "$HOME/jejari-app/jejari.jar.new" "$HOME/jejari-app/jejari.jar"

for port in 8080 8081; do
  sudo systemctl restart "jejari@$port"
  echo -n "jejari@$port 기동 대기"
  for _ in $(seq 1 60); do health "$port" && break; echo -n "."; sleep 3; done
  if ! health "$port"; then
    echo " 실패. journalctl -u jejari@$port -n 80 로 원인을 보세요 (TZ·.env 키 누락이 흔함)."; exit 1
  fi
  echo " UP"
done
DOMAIN=$(grep '^JEJARI_DOMAIN=' .env | cut -d= -f2)
echo "배포 완료: https://${DOMAIN}/actuator/health"
