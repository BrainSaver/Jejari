#!/usr/bin/env bash
# 공용 서버 부하 시험 당일 — A(개선 전후) 두 번 → B(용량) → 결과 표 하나. 계획: docs/강동균/features/공용서버-부하시험-계획서.md
# 결과 문서(칸 미리 만듦): docs/강동균/features/공용서버-부하시험-결과.md
#
#   BASE_URL=https://<서버 주소> loadtest/tools/server_load_day.sh <운행1> <운행2>
#   BASE_URL=... loadtest/tools/server_load_day.sh find          # 빈 운행 고르기 (server_perf.sh find와 같음)
#   BASE_URL=... USERS=50 STEPS=25,50 loadtest/tools/server_load_day.sh <운행1> <운행2>   # 전날 연습 (작게)
#
# 단계마다 멈춰서 물어본다 (Enter = 계속, s = 이 단계 건너뜀, q = 여기서 끝). AUTO=1이면 묻지 않고 끝까지.
# 정합성 점검(check_booking.sh)은 서버에서만 돈다 (Oracle·MongoDB가 서버 docker compose 안) → 할 때가 되면 서버에서 칠 명령을 보여 준다.
#
# 시작 전에 꼭 (계획서 6절):
#   - 관리자 화면 "운영 설정 → 인증 시도 제한 임시 해제"로 이 PC의 IP만 2시간 (가입 시간당 5000 · 로그인 분당 3000, 사유 "부하 시험")
#     ADMIN_TOKEN=<관리자 액세스 토큰>을 주면 해제가 켜져 있는지 이 스크립트가 직접 확인한다 (없으면 물어봄)
#   - 조영준 님: 공지, 자동 배포 끄기, vmstat 켜기
#
# 기본값 (계획서 3절): A = 200명/20초, 한 명이 예매 한 번 · B = 50 → 100 → 200 → 400 → 800명, 단계 90초, 로그인 흩뜨림 30초, 선점 없음
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."      # loadtest/

USERS="${USERS:-200}"
SPREAD_SEC="${SPREAD_SEC:-20}"
RUN_TAG="${RUN_TAG:-perf1}"          # A 계정 (server_perf.sh와 같은 계정을 다시 씀)
CAP_TAG="${CAP_TAG:-c$(date +%d%H%M)}"   # B 계정 — 13_capacity는 시작할 때마다 새로 가입하므로(같은 태그면 가입 실패) 실행마다 새 태그
STEPS="${STEPS:-50,100,200,400,800}"
STEP_SEC="${STEP_SEC:-90}"
LOGIN_SPREAD_SEC="${LOGIN_SPREAD_SEC:-30}"
GAP_SEC="${GAP_SEC:-120}"            # A-1과 A-2 사이 쉬는 시간 (앞 측정의 뒷정리가 끝나게)
FROM="${FROM:-3900023}"
TO="${TO:-3900114}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.1.0}"

[[ -n "${BASE_URL:-}" ]] || { echo "BASE_URL=https://<서버 주소> 가 필요합니다 (주소는 팀 채널)"; exit 2; }
BASE_URL="${BASE_URL%/}"
case "$BASE_URL" in
  http://localhost*|http://127.*) echo "이 스크립트는 공용 서버용입니다 (로컬은 run.sh)"; exit 2 ;;
esac

if [[ "${1:-}" == find ]]; then exec bash tools/server_perf.sh find; fi
RUN1="${1:-}"; RUN2="${2:-}"
[[ "$RUN1" =~ ^[0-9]+$ && "$RUN2" =~ ^[0-9]+$ && "$RUN1" != "$RUN2" ]] \
  || { echo "사용법: $0 <운행1> <운행2>   (서로 다른 빈 운행 — 먼저 '$0 find')"; exit 2; }

k6run() {
  if [[ "${K6_DOCKER:-}" != 1 ]] && command -v k6 >/dev/null 2>&1; then   # K6_DOCKER=1이면 설치된 k6 대신 Docker의 $K6_IMAGE (11-19와 같은 버전)
    k6 "$@"
  else
    local here; here="$(pwd -W 2>/dev/null || pwd)"
    docker run --rm -i -v "${here}:/lt" -w /lt "$K6_IMAGE" "$@"
  fi
}
now() { date '+%F %T'; }

OUT="results/server_day_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT"
LOG="$OUT/timeline.txt"
mark() { echo "$(now)  $*" | tee -a "$LOG"; }

# 단계마다 물어봄. 반환 0 = 실행, 1 = 건너뜀. q면 끝으로
ask() {
  [[ "${AUTO:-}" == 1 ]] && return 0
  local a
  read -r -p $'\n'"▶ $1 — Enter 계속 / s 건너뜀 / q 끝: " a </dev/tty || a=q
  case "$a" in
    s|S) mark "건너뜀: $1"; return 1 ;;
    q|Q) mark "중단: $1 전에 끝냄"; finish; exit 0 ;;
    *) return 0 ;;
  esac
}

# ── 0. 사전 점검 ─────────────────────────────────────────────────────
mark "시작 · 서버 $BASE_URL · A 운행 $RUN1 / $RUN2 · A ${USERS}명/${SPREAD_SEC}초 · B $STEPS (${STEP_SEC}초)"
curl -fsS "$BASE_URL/actuator/health" > /dev/null || { echo "서버 상태 확인 실패: $BASE_URL/actuator/health"; exit 1; }
mark "서버 health OK"

if [[ -n "${ADMIN_TOKEN:-}" ]]; then
  st="$(curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/api/v1/admin/auth-relax" || echo '{}')"
  node -e '
    const s = JSON.parse(process.argv[1] || "{}");
    if (!s.active || !s.includesMe) { console.log("인증 시도 제한 해제가 이 PC IP에 켜져 있지 않습니다 → 관리자 화면에서 먼저 켜세요"); process.exit(1); }
    if (s.remainingMin < 60) { console.log(`해제가 ${s.remainingMin}분 남았습니다 — 시험(약 60분)보다 짧음. 다시 켜세요`); process.exit(1); }
    console.log(`인증 시도 제한 해제 확인: 이 PC 포함 IP ${s.ipCount}개, ${s.remainingMin}분 남음 (자동 종료 ${s.until})`);
  ' "$st" | tee -a "$LOG" || exit 1
else
  echo "ADMIN_TOKEN이 없어 해제 상태를 직접 못 봅니다."
  [[ "${AUTO:-}" == 1 ]] || { read -r -p "관리자 화면에서 이 PC IP만 2시간 해제를 켰나요? (y/N) " y </dev/tty; [[ "$y" == y || "$y" == Y ]] || { echo "먼저 켜세요 (계획서 6절 T−15분)"; exit 1; }; }
  mark "해제 켬 (사람 확인)"
fi

A1_JSON=""; A2_JSON=""; B_JSON=""
finish() {
  local md="$OUT/summary.md"
  {
    echo "# 공용 서버 부하 시험 결과 ($(date '+%F %H:%M'))"
    echo
    echo "- 서버: $BASE_URL · A 운행 $RUN1 / $RUN2 · A ${USERS}명/${SPREAD_SEC}초 (RUN_TAG=$RUN_TAG) · B $STEPS, 단계 ${STEP_SEC}초, 로그인 흩뜨림 ${LOGIN_SPREAD_SEC}초"
    echo "- 서버 커밋·VM CPU·커넥션 대기·GC·정합성은 서버 쪽 값 — 결과 문서(공용서버-부하시험-결과.md)에 옮겨 적기"
    echo
    for x in "A-1:$A1_JSON" "A-2:$A2_JSON"; do
      local name="${x%%:*}" f="${x#*:}"
      echo "## $name"
      if [[ -n "$f" && -s "$f" ]]; then bash tools/server_perf.sh summary "$f" | sed '/^(원본/d'; else echo "(안 돎)"; fi
      echo
    done
    echo "## B 용량"
    if [[ -n "$B_JSON" && -s "$B_JSON" ]]; then
      node -e '
        const r = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
        if (r.error) { console.log("(준비 단계에서 멈춤: " + r.error + ")"); process.exit(0); }
        const f = (v, d = 0) => v == null ? "—" : Number(v).toFixed(d);
        console.log("| 동시 사용자 | 초당 요청 | p95(ms) | 5xx(%) | 바퀴 실패(%) | 입장 대기(초) | 로그인(초) | 판정 |");
        console.log("|---|---|---|---|---|---|---|---|");
        for (const s of r.steps) console.log(`| ${s.users} | ${f(s.rps, 1)} | ${f(s.p95Ms)} | ${f(s.errPct, 2)} | ${f(s.roundFailPct, 1)} | ${f(s.queueWaitSec, 1)} | ${f(s.loginSec, 1)} | ${!s.ran ? "안 돎" : s.ok ? "버팀" : "넘음"} |`);
        console.log();
        console.log("버틴 최대 단계: " + (r.best ? `동시 ${r.best.users}명 · 초당 ${f(r.best.rps, 1)}건` : "없음") + (r.firstFail ? ` · 처음 넘은 단계 ${r.firstFail.users}명` : ""));
      ' "$B_JSON"
    else
      echo "(안 돎)"
    fi
    echo
    echo "## 시각 (Grafana 범위로)"
    echo '```'
    cat "$LOG"
    echo '```'
  } > "$md"
  echo
  echo "결과 표: $OUT/summary.md"
}

# ── 1. A 계정 (이미 있으면 건너뜀) ──────────────────────────────────────
if ask "A 계정 ${USERS}개 준비 (RUN_TAG=$RUN_TAG, 있으면 건너뜀)"; then
  USERS="$USERS" RUN_TAG="$RUN_TAG" BASE_URL="$BASE_URL" bash tools/server_perf.sh accounts 2>&1 | tee "$OUT/accounts.log" | tail -3 | tee -a "$LOG"
fi

# ── 2. A-1 · A-2 ────────────────────────────────────────────────────
runA() { # $1 = 이름, $2 = 운행 → 결과 json 경로를 stdout 마지막 줄로
  local name="$1" run="$2" f="$OUT/$1_${2}.json" rc=0
  mark "$name 시작 (운행 $run)"
  k6run run scenarios/11_user_journey.js --summary-export "${f#./}" \
    -e BASE_URL="$BASE_URL" -e I_OWN_THIS_TARGET=yes -e ACCOUNTS=existing \
    -e RUN_ID="$run" -e USERS="$USERS" -e SPREAD_SEC="$SPREAD_SEC" -e RUN_TAG="$RUN_TAG" \
    -e FROM="$FROM" -e TO="$TO" > "$OUT/$name.log" 2>&1 || rc=$?
  mark "$name 끝 (k6 종료 코드 $rc — 99는 기준 미달일 뿐 결과는 유효)"
  [[ -s "$f" ]] && bash tools/server_perf.sh summary "$f" | sed '/^(원본/d' || tail -20 "$OUT/$name.log"
}
if ask "A-1 개선 전후 (운행 $RUN1, ${USERS}명/${SPREAD_SEC}초, 약 4분)"; then
  runA A-1 "$RUN1"; A1_JSON="$OUT/A-1_${RUN1}.json"
fi
if ask "A-2 (운행 $RUN2) — ${GAP_SEC}초 쉰 뒤 시작"; then
  sleep "$GAP_SEC"
  runA A-2 "$RUN2"; A2_JSON="$OUT/A-2_${RUN2}.json"
fi

cat <<EOF | tee -a "$LOG"

── 조영준 님 (서버 창): A 운행 정합성 ──────────────────────────
  cd ~/Jejari && bash loadtest/tools/check_booking.sh $RUN1 30 && bash loadtest/tools/check_booking.sh $RUN2 30
  FAIL이 하나라도 있으면 B로 가지 말고 원인부터 (계획서 6절 멈추는 기준)
EOF

# ── 3. B 용량 ───────────────────────────────────────────────────────
if ask "B 용량 ($STEPS명, 단계 ${STEP_SEC}초, 계정 만들기 몇 분 포함 — 정합성 OK 확인 뒤)"; then
  mark "B 시작 (RUN_TAG=$CAP_TAG, 배치도 운행 $RUN1)"
  rm -f capacity-result.json
  rc=0
  k6run run scenarios/13_capacity.js \
    -e BASE_URL="$BASE_URL" -e I_OWN_THIS_TARGET=yes -e STEPS="$STEPS" -e STEP_SEC="$STEP_SEC" \
    -e LOGIN_SPREAD_SEC="$LOGIN_SPREAD_SEC" -e RUN_ID="$RUN1" -e FROM="$FROM" -e TO="$TO" -e RUN_TAG="$CAP_TAG" \
    2>&1 | tee "$OUT/B.log" | grep --line-buffered -E "용량 시험|계정|/[0-9]+ \(|사전 점검|사용자 \||^ +[0-9]+ \||버틴|넘은|기준|멈|ERRO" || rc=$?
  [[ -s capacity-result.json ]] && mv capacity-result.json "$OUT/B_capacity.json" && B_JSON="$OUT/B_capacity.json"
  mark "B 끝"
fi

cat <<EOF | tee -a "$LOG"

── 조영준 님 (서버 창): C 정합성 + 서버 값 ─────────────────────────
  cd ~/Jejari && bash loadtest/tools/check_booking.sh all 60
  Grafana "제자리 · 앱 개요"에서 B 구간(위 시각)의 CPU · 커넥션 대기 · 5xx · 로그인 대기실 읽기, vmstat 끄기
되돌리기 (계획서 7절): 해제 끝내기 · 자동 배포 타이머 다시 켜기 · 부하 계정 위험 케이스 정리 여부
EOF
finish
