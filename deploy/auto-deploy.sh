#!/usr/bin/env bash
# 자동 배포 (서버에서 systemd 타이머가 10분마다 실행: jejari-autodeploy.timer)
#
# main에 새 커밋이 있으면 아래를 모두 통과할 때만 deploy.sh로 배포한다.
#   1) 그 커밋의 CI(GitHub Actions)가 성공 — 아직 도는 중이면 다음 차례에, 실패면 건너뜀
#   2) 앱에 영향을 주는 파일이 바뀜 — 문서만 바뀌었으면 빌드하지 않고 "반영됨"으로만 기록
#   3) DB·인프라가 안 바뀜 — ERD/DDL·시드·패치·compose·Caddy가 바뀌면 자동 배포를 멈추고 사람을 기다림
#      (코드만 배포하면 옛 DB 제약에 걸릴 수 있음. DB를 맞춘 뒤 `bash deploy/deploy.sh`를 직접 실행하면 풀림)
#
# 서버에 비밀값을 더 두지 않는다: 공개 저장소를 받고, CI 결과는 공개 GitHub API로 본다 (시간당 60회 한도 안).
# 상태: ~/jejari-app/autodeploy.state (OK / WAITING_CI / CI_FAILED / BLOCKED / FAILED), 로그: journalctl -u jejari-autodeploy
set -uo pipefail
cd "$(dirname "$0")/.."

REPO="${JEJARI_REPO:-BrainSaver/Jejari}"
APP="$HOME/jejari-app"
DEPLOYED="$APP/deployed-sha"
STATE="$APP/autodeploy.state"
mkdir -p "$APP"

# 겹쳐 돌지 않게 (빌드가 10분을 넘겨도 다음 실행은 건너뜀)
exec 9>"$APP/autodeploy.lock"
flock -n 9 || { echo "이전 실행이 아직 진행 중 → 건너뜀"; exit 0; }

state() { echo "$(date '+%F %T') $*" | tee "$STATE"; }

git fetch -q origin main || { echo "git fetch 실패 (네트워크?) → 다음 차례에"; exit 0; }
NEW=$(git rev-parse origin/main)
CUR=$(cat "$DEPLOYED" 2>/dev/null || git rev-parse HEAD)
[[ "$NEW" == "$CUR" ]] && exit 0
SHORT=${NEW:0:7}

# 이미 실패·차단으로 판단한 커밋이면 다시 하지 않음 (새 커밋이 오면 다시 판단)
if grep -q " $SHORT " "$STATE" 2>/dev/null && ! grep -q "WAITING_CI" "$STATE"; then
  exit 0
fi

# 1) CI 결과 (이 커밋에 붙은 check run 전부)
api=$(curl -fsS -m 20 -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/$REPO/commits/$NEW/check-runs?per_page=50") \
  || { echo "GitHub API 실패 → 다음 차례에"; exit 0; }
total=$(grep -o '"total_count": *[0-9]*' <<<"$api" | grep -o '[0-9]*$' || echo 0)
pending=$(grep -c '"status": *"\(queued\|in_progress\|waiting\|pending\)"' <<<"$api" || true)
bad=$(grep -o '"conclusion": *"[a-z_]*"' <<<"$api" | grep -vc '"success"\|"skipped"\|"neutral"' || true)
if [[ "$total" == "0" || "$pending" != "0" ]]; then
  state "WAITING_CI $SHORT CI가 아직 끝나지 않음 (check run $total개, 진행 중 $pending개)"; exit 0
fi
if [[ "$bad" != "0" ]]; then
  state "CI_FAILED $SHORT CI 실패·취소 $bad개 → 배포 안 함 (다음 커밋을 기다림)"; exit 0
fi

# 2)·3) 바뀐 파일
changed=$(git diff --name-only "$CUR" "$NEW")
blocking=$(grep -E '^(docs/erd/|db/|compose\.yaml$|docker/|deploy/compose\.server\.yaml$|deploy/Caddyfile$|deploy/prometheus\.server\.yml$|deploy/jejari@\.service$)' <<<"$changed" || true)
if [[ -n "$blocking" ]]; then
  state "BLOCKED $SHORT DB·인프라 변경이 있어 자동 배포를 멈춤 → DB 패치·compose 반영 뒤 'bash deploy/deploy.sh' 직접 실행: $(echo $blocking | cut -c1-200)"
  exit 0
fi
appfiles=$(grep -E '^(src/main/|frontend/|build\.gradle$|settings\.gradle$|gradle/|gradlew$|deploy/deploy\.sh$)' <<<"$changed" || true)
if [[ -z "$appfiles" ]]; then
  git merge -q --ff-only "$NEW" 2>/dev/null || true
  echo "$NEW" > "$DEPLOYED"
  state "OK $SHORT 앱에 영향 없는 변경만 (문서·테스트 등) → 빌드 없이 반영으로 기록"; exit 0
fi

# 배포 (이 커밋으로 고정: 그사이 새로 올라온 커밋은 CI를 확인한 뒤 다음 차례에)
echo "자동 배포 시작: ${CUR:0:7} → $SHORT (앱 파일 $(wc -l <<<"$appfiles")개)"
if DEPLOY_SHA="$NEW" bash deploy/deploy.sh; then
  state "OK $SHORT 자동 배포 완료"
else
  state "FAILED $SHORT deploy.sh 실패 → 살아 있는 한 대가 옛 버전으로 응답 중일 수 있음. journalctl -u jejari-autodeploy 확인"
  exit 1
fi
