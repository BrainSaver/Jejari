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
if [[ -n "${DEPLOY_SHA:-}" ]]; then
  # 정확히 그 커밋으로 (merge --ff-only는 작업 트리가 이미 더 앞서 있으면 아무것도 안 해서, CI를 확인하지 않은 커밋이 빌드될 수 있음)
  git checkout -q --detach "$DEPLOY_SHA"
else
  git checkout -q "$BRANCH"
  git merge -q --ff-only "$TARGET"
fi
echo "빌드: $(git log --oneline -1)"

# ── DB 패치 확인 (11-23) ─────────────────────────────────────────────
# deploy.sh는 코드만 올린다. DB 패치는 자동 배포가 BLOCKED일 때 A-08 [적용]으로 들어가는데, 사람이 이 스크립트를
# 직접 돌리면(특히 CI가 멈춰 자동 배포가 BLOCKED까지 가지 못할 때) 패치가 빠진 채 새 코드가 올라간다.
# 11-23: v33 없이 비회원 예매 코드가 올라가 ORA-00904 130건 (docs/조영준/devlog/20261123-…).
# 그래서 지난 배포 이후 새로 생기거나 바뀐 db/patch/vNN.sql 중 적용 기록(~/jejari-app/db-patches.applied)이 없는 것이 있으면
#   기본: 목록을 보여 주고 빌드 전에 멈춘다
#   APPLY_PATCHES=1: 그 패치를 버전 순서대로 넣고(앱 계정, 패치는 다시 실행해도 안전한 것이 규칙) 기록한 뒤 계속
#   SKIP_PATCH_CHECK=1: 이미 손으로 넣었을 때 (기록이 없어도 진행)
APP_DIR="$HOME/jejari-app"
PREV=$(cat "$APP_DIR/deployed-sha" 2>/dev/null || true)
if [[ -n "$PREV" && "${SKIP_PATCH_CHECK:-}" != 1 ]] && git cat-file -e "$PREV^{commit}" 2>/dev/null; then
  pending=()
  while read -r p; do
    [[ -n "$p" ]] || continue
    grep -qE " ${p//./\\.} .*OK\$" "$APP_DIR/db-patches.applied" 2>/dev/null || pending+=("$p")
  done < <(git diff --name-only --diff-filter=AM "$PREV" HEAD -- 'db/patch/v*.sql' | sort -V)
  if (( ${#pending[@]} )); then
    if [[ "${APPLY_PATCHES:-}" != 1 ]]; then
      echo "DB 패치가 아직 적용되지 않았습니다 (지난 배포 ${PREV:0:7} 이후): ${pending[*]}"
      echo "  내용 확인: git diff ${PREV:0:7} HEAD -- ${pending[*]}"
      echo "  넣고 배포: APPLY_PATCHES=1 bash deploy/deploy.sh   (이미 손으로 넣었으면 SKIP_PATCH_CHECK=1)"
      echo "  빌드하지 않고 멈춤 (서버 앱은 그대로 ${PREV:0:7})"
      exit 1
    fi
    export COMPOSE_FILE="${COMPOSE_FILE:-compose.yaml:deploy/compose.server.yaml}"
    for p in "${pending[@]}"; do
      echo "DB 패치 적용: $p"
      out=$(docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
              bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"' < "$p" 2>&1) \
        || { echo "$out" | tail -20; echo "$p 실행 실패 → 배포 중단"; exit 1; }
      if grep -qE '^(ORA|SP2|PLS)-[0-9]+' <<<"$out"; then
        grep -E '^(ORA|SP2|PLS)-[0-9]+' <<<"$out" | head -5; echo "$p 에서 오류 → 배포 중단 (앱은 그대로 ${PREV:0:7})"; exit 1
      fi
      echo "$(date '+%F %T') $(git rev-parse --short HEAD) $p deploy.sh OK" >> "$APP_DIR/db-patches.applied"
    done
  fi
fi

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

# 켜 둔(enabled) 앱만 재시작. 11-19부터 1대(8080) 운영 (PERF-01: CPU 4개 VM에서 2대가 더 느림).
# 2대로 되돌리려면 sudo systemctl enable --now jejari@8081 + Caddyfile·prometheus.server.yml의 8081 줄 되살리기
for port in 8080 8081; do
  systemctl is-enabled -q "jejari@$port" 2>/dev/null || continue
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

# 코드 밖에서 사람이 할 일 알림 (11-23: 경보 규칙을 배포하고 Grafana 재시작을 빠뜨림). 실행은 하지 않고 보여 주기만
if [[ -n "$PREV" ]] && git cat-file -e "$PREV^{commit}" 2>/dev/null; then
  C="docker compose -f compose.yaml -f deploy/compose.server.yaml"
  todo=$(git diff --name-only "$PREV" HEAD | awk -v c="$C" '
    /^deploy\/grafana-alerting\//                 { a[c " restart grafana"] = 1 }
    /^deploy\/Caddyfile$/                          { a[c " restart caddy"] = 1 }
    /^deploy\/prometheus\.server\.yml$/            { a[c " restart prometheus"] = 1 }
    /^(compose\.yaml|deploy\/compose\.server\.yaml)$/ { a[c " up -d"] = 1 }
    /^docker\/mongo\/mongo-init\.js$/              { a["mongo-init.js 변경: 기존 DB에는 자동 적용 안 됨 → 바뀐 색인·컬렉션을 mongosh로 직접 (deploy/README.md)"] = 1 }
    /^deploy\/(jejari[@-][a-z-]*\.(service|timer)|sudoers-jejari)$/ { a["systemd 유닛·sudoers 변경: 구글 계정 사용자로 설치 (deploy/README.md \"timer·service·sudoers 설치\")"] = 1 }
    END { for (k in a) print "  - " k }')
  if [[ -n "$todo" ]]; then echo "서버에서 따로 할 일 (지난 배포 ${PREV:0:7} 이후 바뀐 설정):"; echo "$todo"; fi
fi
exit 0   # 위 알림은 실패가 아님 (auto-deploy.sh가 종료 코드로 성공을 판단)
