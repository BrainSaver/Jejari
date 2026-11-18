#!/usr/bin/env bash
# 앱 배포·재배포 (서버에서). main을 받아 빌드하고 앱 2대를 한 대씩 재시작합니다.
#   cd ~/Jejari && bash deploy/deploy.sh            (BRANCH=main 기본)
#   DEPLOY_SHA=<커밋> bash deploy/deploy.sh          (그 커밋으로 고정. 자동 배포가 CI를 확인한 커밋만 올릴 때)
#
# 한 대씩 재시작하는 이유: Caddy가 살아 있는 쪽으로 보내 배포 중에도 요청이 끊기지 않게
# 끝나면 ~/jejari-app/deployed-sha에 배포한 커밋을 남긴다 (자동 배포가 비교 기준으로 씀.
# 자동 배포가 DB 변경으로 멈췄을 때 DB를 맞추고 이 스크립트를 직접 돌리면 그 기준이 앞으로 가서 풀린다)
set -euo pipefail
cd "$(dirname "$0")/.."
BRANCH="${BRANCH:-main}"
TARGET="${DEPLOY_SHA:-origin/$BRANCH}"

health() { curl -fs "http://127.0.0.1:$1/actuator/health" | grep -q '"status":"UP"'; }

# 인프라가 떠 있는지 (Oracle healthy = 앱 계정으로 FREEPDB1 접속까지 확인됨)
[[ "$(docker inspect -f '{{.State.Health.Status}}' jejari-oracle-1 2>/dev/null)" == healthy ]] \
  || { echo "Oracle이 healthy가 아닙니다. docker compose -f compose.yaml -f deploy/compose.server.yaml up -d 후 기다리세요."; exit 1; }

git fetch -q origin "$BRANCH"
git checkout -q "$BRANCH"
git merge -q --ff-only "$TARGET"
echo "빌드: $(git log --oneline -1)"

# 화면 (frontend/, React + Vite): dist/를 만들어 두면 bootJar가 jar의 static/에 넣는다 (build.gradle).
# 서버에 Node를 설치하지 않고 Node 22 컨테이너로 빌드. 빠뜨리면 API만 올라가 "/"가 NOT_FOUND
if [[ -f frontend/package.json ]]; then
  rm -rf frontend/dist
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD/frontend:/app" -w /app node:22-alpine \
    sh -c 'npm ci --no-audit --no-fund --loglevel=error && npm run build' > /tmp/jejari-frontend.log 2>&1 \
    || { echo "화면 빌드 실패: /tmp/jejari-frontend.log"; exit 1; }
  [[ -f frontend/dist/index.html ]] || { echo "화면 빌드 결과(dist/index.html)가 없습니다."; exit 1; }
  echo "화면 빌드 완료"
fi
LC_ALL=C.UTF-8 TZ=Asia/Seoul ./gradlew -q bootJar   # LC_ALL: 한글 파일 이름 클래스

JAR=$(ls -t build/libs/*.jar | grep -v -- '-plain' | head -1)
if [[ -f frontend/package.json ]] && ! jar tf "$JAR" | grep -q 'BOOT-INF/classes/static/index.html'; then
  echo "jar에 화면(static/index.html)이 없습니다. 배포를 멈춥니다."; exit 1
fi
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
git rev-parse HEAD > "$HOME/jejari-app/deployed-sha"
DOMAIN=$(grep '^JEJARI_DOMAIN=' .env | cut -d= -f2)
echo "배포 완료: https://${DOMAIN}/actuator/health ($(git log --oneline -1 | cut -c1-60))"
