#!/usr/bin/env bash
# 공용 서버 성능 측정 (PERF-01 개선 전후) — 시나리오 11(로그인 → 조회 → 대기열 → 배치도 → 선점 → 결제)을 내 PC에서 서버로.
# 체크리스트: docs/강동균/features/공용서버-성능측정-체크리스트.md (서버 쪽 준비·되돌리기는 거기)
#
#   BASE_URL=https://<서버 주소> loadtest/tools/server_perf.sh find              # 빈 운행 고르기
#   BASE_URL=... loadtest/tools/server_perf.sh accounts                          # 측정용 계정 USERS명 (한 번만, 이어서 가능)
#   BASE_URL=... loadtest/tools/server_perf.sh run <운행 번호> [k6 -e 인자...]   # 측정 + 표 요약
#   loadtest/tools/server_perf.sh summary <결과 json>                            # 이미 잰 결과를 다시 표로
#
# 기본값은 11-19 조영준 님 측정과 같은 조건: 200명이 20초 안에 흩어져 도착, 한 명이 예매 한 번.
#   USERS=200 SPREAD_SEC=20 RUN_TAG=perf1 FROM=3900023(서울) TO=3900114(부산)
# k6가 PATH에 있으면 그것을, 없으면 Docker의 grafana/k6:1.1.0을 쓴다 (11-19와 같은 버전).
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash가 /lt 같은 경로를 C:/... 로 바꾸지 않게
cd "$(dirname "$0")/.."      # loadtest/

USERS="${USERS:-200}"
SPREAD_SEC="${SPREAD_SEC:-20}"
RUN_TAG="${RUN_TAG:-perf1}"
FROM="${FROM:-3900023}"
TO="${TO:-3900114}"
DAYS="${DAYS:-14}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.1.0}"

need_url() {
  [[ -n "${BASE_URL:-}" ]] || { echo "BASE_URL=https://<서버 주소> 가 필요합니다 (주소는 팀 채널)"; exit 2; }
  BASE_URL="${BASE_URL%/}"
}

k6run() {
  if command -v k6 >/dev/null 2>&1; then
    k6 "$@"
  else
    local here; here="$(pwd -W 2>/dev/null || pwd)"   # Windows Git Bash면 C:/... 형식
    docker run --rm -i -v "${here}:/lt" -w /lt "$K6_IMAGE" "$@"
  fi
}

cmd_find() {
  need_url
  curl -fsS -o /dev/null "$BASE_URL/actuator/health" || { echo "서버 상태 확인 실패: $BASE_URL/actuator/health"; exit 1; }
  echo "판매 중인 운행 ($FROM → $TO, 오늘부터 ${DAYS}일, 남은 좌석 많은 순)"
  local all="[]" d day
  for ((d = 0; d < DAYS; d++)); do
    day="$(date -d "+${d} days" +%F 2>/dev/null || date -v+"${d}"d +%F)"
    local one
    one="$(curl -fsS "$BASE_URL/api/v1/runs?from=$FROM&to=$TO&date=$day" || echo '[]')"
    all="$(node -e 'const a=JSON.parse(process.argv[1]),b=JSON.parse(process.argv[2]);process.stdout.write(JSON.stringify(a.concat(b)))' "$all" "$one")"
  done
  node -e '
    const runs = JSON.parse(process.argv[1]).filter((r) => r.bookable && r.remaining);
    const left = (r) => Object.values(r.remaining).reduce((s, n) => s + (n || 0), 0);
    runs.sort((a, b) => left(b) - left(a));
    if (!runs.length) { console.log("  예매 가능한 운행이 없습니다 (FROM·TO·DAYS를 바꿔 보세요)"); process.exit(1); }
    const users = Number(process.argv[2]);
    console.log("  운행번호   열차      출발               남은좌석");
    for (const r of runs.slice(0, 10)) {
      const warn = left(r) < users ? "  ← 사용자 " + users + "명보다 적음" : "";
      console.log("  " + String(r.runId).padEnd(10) + " " + (r.trainKind + " " + r.trainNo).padEnd(9) + " " + String(r.departureAt).slice(0, 16).padEnd(18) + " " + left(r) + warn);
    }
    console.log("\n  측정마다 다른 운행을 쓰고, 개선 전후 비교는 같은 열차(좌석 수가 같은 것)끼리 하세요.");
  ' "$all" "$USERS"
}

cmd_accounts() {
  need_url
  echo "계정 ${USERS}개 준비 (RUN_TAG=$RUN_TAG). 이미 있는 계정은 건너뜁니다."
  k6run run scenarios/11_user_journey.js \
    -e BASE_URL="$BASE_URL" -e I_OWN_THIS_TARGET=yes -e ACCOUNTS=prepare \
    -e USERS="$USERS" -e RUN_TAG="$RUN_TAG"
}

cmd_run() {
  need_url
  local run_id="${1:?운행 번호가 필요합니다 (find로 고르기)}"; shift
  [[ "$run_id" =~ ^[0-9]+$ ]] || { echo "운행 번호는 숫자: $run_id"; exit 2; }
  mkdir -p results
  local out="results/perf_${run_id}_$(date +%Y%m%d_%H%M%S).json"
  echo "측정 시작 $(date '+%F %T') · 운행 $run_id · ${USERS}명/${SPREAD_SEC}초 · RUN_TAG=$RUN_TAG"
  echo "(서버 창에서 vmstat을 켜 두었는지 확인 — 체크리스트 3단계)"
  local rc=0
  k6run run scenarios/11_user_journey.js --summary-export "$out" \
    -e BASE_URL="$BASE_URL" -e I_OWN_THIS_TARGET=yes -e ACCOUNTS=existing \
    -e RUN_ID="$run_id" -e USERS="$USERS" -e SPREAD_SEC="$SPREAD_SEC" -e RUN_TAG="$RUN_TAG" \
    -e FROM="$FROM" -e TO="$TO" "$@" || rc=$?
  echo "측정 끝 $(date '+%F %T') (k6 종료 코드 $rc — 99는 기준 미달일 뿐 결과는 유효)"
  [[ -s "$out" ]] && cmd_summary "$out"
  return 0
}

cmd_summary() {
  local f="${1:?결과 json 파일}"
  node -e '
    const m = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")).metrics || {};
    const s = (v) => v == null ? "-" : v >= 1000 ? (v / 1000).toFixed(2) + "초" : Math.round(v) + "ms";
    const p95 = (k) => (m[k] || {})["p(95)"];
    const cnt = (k) => (m[k] || {}).count || 0;
    const ok = m.journey_success || {};
    const pass = ok.passes ?? 0, total = (ok.passes ?? 0) + (ok.fails ?? 0);
    const rows = [
      ["예매 성공", total ? `${pass}/${total} (${(100 * pass / total).toFixed(1)}%)` : "-"],
      ["예매 전체 p95", s(p95("journey_ms"))],
      ["로그인 p95", s(p95("http_req_duration{name:login}"))],
      ["대기열 진입 p95", s(p95("http_req_duration{name:queue_enter}"))],
      ["좌석 배치도 p95", s(p95("http_req_duration{name:seat_map}"))],
      ["좌석 선점 p95", s(p95("http_req_duration{name:hold}"))],
      ["결제 p95", s(p95("http_req_duration{name:pay}"))],
      ["대기열 대기 p95", (m.queue_wait_sec || {})["p(95)"] != null ? m.queue_wait_sec["p(95)"].toFixed(1) + "초" : "-"],
      ["처리한 요청", (m.http_reqs || {}).rate != null ? "초당 " + m.http_reqs.rate.toFixed(1) : "-"],
      ["좌석 경합(다른 좌석 재시도)", String(cnt("seat_conflict"))],
      ["멈춘 사람 (단계는 k6 로그)", String(cnt("journey_failed"))],
      ["매진으로 끝남", String(cnt("journey_sold_out"))],
    ];
    console.log("\n| 항목 | 값 |\n|---|---|");
    for (const [k, v] of rows) console.log(`| ${k} | ${v} |`);
    console.log(`\n(원본: ${process.argv[1]}) — VM CPU·커넥션 대기·GC는 체크리스트 4단계에서 서버 쪽 값을 더해 적으세요`);
  ' "$f"
}

case "${1:-}" in
  find) shift; cmd_find "$@" ;;
  accounts) shift; cmd_accounts "$@" ;;
  run) shift; cmd_run "$@" ;;
  summary) shift; cmd_summary "$@" ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
