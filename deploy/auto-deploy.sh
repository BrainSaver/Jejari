#!/usr/bin/env bash
# 자동 배포 (서버에서 systemd 타이머가 10분마다 실행: jejari-autodeploy.timer)
#
# main에 새 커밋이 있으면 아래를 모두 통과할 때만 deploy.sh로 배포한다.
#   1) CI(GitHub Actions)가 성공한 커밋 — main 최신부터 거꾸로 5개 중 가장 최근 성공 커밋 (진행 중·실패는 건너뜀)
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

SINCE="$APP/autodeploy.since"   # 지금 상태(OK·WAITING_CI…)가 시작된 시각. 같은 상태가 이어지면 그대로 둠
state() {
  local prev; prev=$(awk '{print $3}' "$STATE" 2>/dev/null)
  [[ "$prev" == "$1" && -f "$SINCE" ]] || date +%s > "$SINCE"
  echo "$(date '+%F %T') $*" | tee "$STATE"
}

# 실행이 어떻게 끝나든(정상·조기 종료·실패) 상태를 Pushgateway에 보고 → Grafana 경보 (deploy/grafana-alerting/)
#   jejari_autodeploy_state{state}        지금 상태 (OK / WAITING_CI / CI_FAILED / BLOCKED / FAILED)
#   jejari_autodeploy_state_age_seconds   그 상태가 된 뒤 지난 시간 (CI 대기가 너무 길면 경보)
#   jejari_autodeploy_last_run_timestamp_seconds  마지막 실행 시각 (타이머가 멈추면 경보)
#   jejari_autodeploy_behind_commits      배포 안 된 main 커밋 수
report() {
  local s age now behind
  now=$(date +%s)
  s=$(awk '{print $3}' "$STATE" 2>/dev/null); s=${s:-OK}
  age=$(( now - $(cat "$SINCE" 2>/dev/null || echo "$now") ))
  behind=$(git rev-list --count --first-parent "$(cat "$DEPLOYED" 2>/dev/null || echo HEAD)..origin/main" 2>/dev/null || echo 0)
  curl -fsS -m 5 -X PUT --data-binary @- "http://127.0.0.1:9091/metrics/job/jejari_autodeploy" >/dev/null 2>&1 <<EOF || true
# TYPE jejari_autodeploy_state gauge
jejari_autodeploy_state{state="$s"} 1
# TYPE jejari_autodeploy_state_age_seconds gauge
jejari_autodeploy_state_age_seconds $age
# TYPE jejari_autodeploy_last_run_timestamp_seconds gauge
jejari_autodeploy_last_run_timestamp_seconds $now
# TYPE jejari_autodeploy_behind_commits gauge
jejari_autodeploy_behind_commits $behind
EOF
}
trap report EXIT

git fetch -q origin main || { echo "git fetch 실패 (네트워크?) → 다음 차례에"; exit 0; }
HEAD_MAIN=$(git rev-parse origin/main)
CUR=$(cat "$DEPLOYED" 2>/dev/null || git rev-parse HEAD)
[[ "$HEAD_MAIN" == "$CUR" ]] && exit 0

# CI 결과: success | pending | failed
ci() {
  local api total pending bad
  api=$(curl -fsS -m 20 -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/$REPO/commits/$1/check-runs?per_page=50") || { echo error; return; }
  total=$(grep -o '"total_count": *[0-9]*' <<<"$api" | grep -o '[0-9]*$' || echo 0)
  pending=$(grep -c '"status": *"\(queued\|in_progress\|waiting\|pending\)"' <<<"$api" || true)
  bad=$(grep -o '"conclusion": *"[a-z_]*"' <<<"$api" | grep -vc '"success"\|"skipped"\|"neutral"' || true)
  if [[ "$total" == "0" || "$pending" != "0" ]]; then echo pending
  elif [[ "$bad" != "0" ]]; then echo failed
  else echo success; fi
}

# 1) 배포할 커밋 고르기: main의 최신부터 거꾸로, CI가 성공한 가장 최근 커밋 (최대 5개 확인).
#    최신만 기다리면 커밋이 CI(3~5분)보다 자주 올라오는 날엔 한 번도 배포하지 못한다
NEW=""; seen=""
for sha in $(git rev-list --first-parent --max-count=5 "$CUR..$HEAD_MAIN"); do
  r=$(ci "$sha"); seen+="${sha:0:7}=$r "
  [[ "$r" == error ]] && { echo "GitHub API 실패 → 다음 차례에"; exit 0; }
  [[ "$r" == success ]] && { NEW=$sha; break; }
done
if [[ -z "$NEW" ]]; then
  state "WAITING_CI ${HEAD_MAIN:0:7} CI가 성공한 새 커밋이 아직 없음 ($seen)"; exit 0
fi
SHORT=${NEW:0:7}

# 이미 차단·실패로 판단한 커밋이면 다시 하지 않음 (더 새 커밋이 CI를 통과하면 그때 다시 판단)
if grep -qE "^[0-9-]+ [0-9:]+ (BLOCKED|FAILED) $SHORT " "$STATE" 2>/dev/null; then
  exit 0
fi

# 2)·3) 바뀐 파일
changed=$(git diff --name-only "$CUR" "$NEW")
# 사람이 적용해야 하는 것: DB·시드·compose·Caddy·Prometheus 설정, systemd 유닛(설치에 sudo), 경보 규칙(Grafana 재시작), sudoers
blocking=$(grep -E '^(docs/erd/|db/|compose\.yaml$|docker/|deploy/compose\.server\.yaml$|deploy/Caddyfile$|deploy/prometheus\.server\.yml$|deploy/jejari[@-][a-z]*\.(service|timer)$|deploy/jejari@\.service$|deploy/grafana-alerting/|deploy/sudoers-jejari$)' <<<"$changed" || true)
if [[ -n "$blocking" ]]; then
  state "BLOCKED $SHORT DB·인프라 변경이 있어 자동 배포를 멈춤 → DB 패치·compose 반영 뒤 'bash deploy/deploy.sh' 직접 실행: $(echo $blocking | cut -c1-200)"
  exit 0
fi
appfiles=$(grep -E '^(src/main/|frontend/|build\.gradle$|settings\.gradle$|gradle/|gradlew$|deploy/deploy\.sh$)' <<<"$changed" || true)
if [[ -z "$appfiles" ]]; then
  git checkout -q --detach "$NEW" 2>/dev/null || true   # 작업 트리도 그 커밋으로 (스크립트 자신의 변경도 여기서 반영됨)
  echo "$NEW" > "$DEPLOYED"
  state "OK $SHORT 앱에 영향 없는 변경만 (문서·테스트 등) → 빌드 없이 반영으로 기록"; exit 0
fi

# 배포 (이 커밋으로 고정: 그사이 새로 올라온 커밋은 CI를 확인한 뒤 다음 차례에)
echo "자동 배포 시작: ${CUR:0:7} → $SHORT (앱 파일 $(wc -l <<<"$appfiles")개)"
if DEPLOY_SHA="$NEW" bash deploy/deploy.sh; then
  done_sha=$(cut -c1-7 "$DEPLOYED")
  [[ "$done_sha" == "$SHORT" ]] || { state "FAILED $SHORT 배포한 커밋($done_sha)이 고른 커밋과 다름 → 확인 필요"; exit 1; }
  state "OK $SHORT 자동 배포 완료"
else
  state "FAILED $SHORT deploy.sh 실패 → 살아 있는 한 대가 옛 버전으로 응답 중일 수 있음. journalctl -u jejari-autodeploy 확인"
  exit 1
fi
