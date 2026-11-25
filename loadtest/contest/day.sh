#!/usr/bin/env bash
# 봇 대회 날, 집계자 PC에서 돌리는 준비·정리 스크립트.
#
#   ADMIN_ID=… ADMIN_PW=… loadtest/contest/day.sh start     # 이 PC만 가입·로그인 한도 해제(자동 종료) → 대회 운행 → 접속 주소
#   ADMIN_ID=… ADMIN_PW=… loadtest/contest/day.sh restore   # 대회가 끝나면 해제를 바로 끝냄 (잊어도 시간이 되면 저절로 끝남)
#
# 왜 한도를 푸나: 가입(본인인증 포함)은 IP당 1시간에 10번 시도까지다 (app_policy auth.signup_rate_per_hour).
# 봇 네 개가 한 PC에서 명의 24개를 만들면 막힌다.
#
# 어떻게 푸나 (2026-11-25부터): 인증 시도 제한 임시 해제(ERD v39, 관리자 화면 "운영 설정")로 이 PC의 IP만,
# RELAX_HOURS(기본 4)시간 동안. 시간이 지나면 앱이 읽을 때 평소 한도로 돌아가므로 restore를 잊어도 된다.
# 예전에는 전체 한도(auth.signup_rate_per_hour)를 100으로 올렸다가 restore를 4일 동안 잊은 적이 있다 (11-19 ~ 11-23).
# 다른 기기에서 들어오는 사람 참가자는 평소 한도 (사람 한 명이 계정 하나면 충분). 사람 한 명이 기기 하나로 계정을
# 여러 개 만드는 시험이면 RAISE_GLOBAL=yes로 예전처럼 전체 한도도 올린다 — 이때는 restore를 꼭.
# 위험 점수 규칙·정책은 건드리지 않는다 (대회 동안 수비 고정).
set -euo pipefail
cd "$(dirname "$0")/../.."

CMD="${1:-}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
STATE=".contest-day"   # RAISE_GLOBAL=yes일 때 원래 전체 한도를 적어 두는 파일 (gitignore)
RELAX_HOURS="${RELAX_HOURS:-4}"
KEY="auth.signup_rate_per_hour"
: "${ADMIN_ID:?ADMIN_ID 환경 변수가 필요합니다 (SUPERVISOR 관리자)}"
: "${ADMIN_PW:?ADMIN_PW 환경 변수가 필요합니다}"

val() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }
call() {
  local out code
  out=$(curl -sS -w '\n%{http_code}' -X "$1" "${BASE_URL}$2" -H 'Content-Type: application/json' \
    ${TOKEN:+-H "Authorization: Bearer ${TOKEN}"} ${3:+--data "$3"})
  code=${out##*$'\n'}; out=${out%$'\n'*}
  if [[ "$code" != 2* ]]; then echo "실패: $1 $2 → HTTP ${code} ${out}" >&2; exit 1; fi
  printf '%s' "$out"
}
login() {
  curl -sf "${BASE_URL}/actuator/health" > /dev/null || { echo "앱(${BASE_URL})이 떠 있지 않습니다"; exit 1; }
  TOKEN=$(call POST /api/v1/admin/auth/login "{\"loginId\":\"${ADMIN_ID}\",\"password\":\"${ADMIN_PW}\"}" | val accessToken)
}
set_policy() { # $1 값
  local cur ver
  cur=$(call GET "/api/v1/admin/policies/${KEY}")
  ver=$(printf '%s' "$cur" | val version)
  call PUT "/api/v1/admin/policies/${KEY}" "{\"value\":\"$1\",\"version\":${ver}}" > /dev/null
}

# 이 PC만 한도 해제. 사유는 영문으로 (Windows Git Bash의 curl이 한글 본문을 깨뜨려 400)
relax_start() {
  local body="{\"hours\":${RELAX_HOURS},\"reason\":\"bot contest day.sh, auto off in ${RELAX_HOURS}h\""
  call POST /api/v1/admin/auth-relax "${body},\"ip\":\"\"}" > /dev/null   # 비우면 이 요청을 보낸 IP
  # 앱이 이 PC에 있으면 봇은 127.0.0.1 또는 ::1로 들어온다. 앱은 ::1을 0:0:0:0:0:0:0:1로 본다 → 두 모양 모두
  if [[ "$BASE_URL" =~ ://(localhost|127\.0\.0\.1|\[::1\]) ]]; then
    for ip in 127.0.0.1 0:0:0:0:0:0:0:1; do call POST /api/v1/admin/auth-relax "${body},\"ip\":\"${ip}\"}" > /dev/null; done
  fi
  call GET /api/v1/admin/auth-relax | sed -n 's/.*"until":"\{0,1\}\([^",}]*\).*/  해제 종료 시각: \1 (그때 저절로 평소 한도)/p'
  echo
}

case "$CMD" in
  start)
    login
    relax_start
    echo "가입·로그인 한도 해제: 이 PC(봇)만, ${RELAX_HOURS}시간. restore를 잊어도 시간이 되면 저절로 끝납니다"
    if [[ "${RAISE_GLOBAL:-no}" == yes ]]; then
      before=$(call GET "/api/v1/admin/policies/${KEY}" | val value)
      [[ -f "$STATE" ]] || echo "$before" > "$STATE"
      echo "주의: RAISE_GLOBAL=yes — 전체 가입 한도 ${KEY}: ${before} → 100. 이건 저절로 안 돌아갑니다. 끝나면 반드시 day.sh restore"
      set_policy 100
    fi

    echo
    bash loadtest/contest/setup.sh "$BASE_URL"

    echo
    echo "사람 참가자 접속 주소 (같은 와이파이):"
    if command -v ip > /dev/null; then
      # Docker가 만든 가상 랜카드(docker0·br-·veth)는 빼고 실제 랜카드 주소만
      ip -4 -o addr show scope global | awk '$2 !~ /^(docker|br-|veth)/ {split($4, a, "/"); print "  http://" a[1] ":8080"}'
    elif command -v hostname > /dev/null && hostname -I > /dev/null 2>&1; then
      for ip in $(hostname -I); do [[ "$ip" == *.*.*.* ]] && echo "  http://${ip}:8080"; done
    else
      echo "  이 PC의 IP를 확인하세요 (Windows: ipconfig, macOS: ipconfig getifaddr en0) → http://<IP>:8080"
    fi
    echo "  다른 기기에서 안 열리면 방화벽에서 8080 포트(Java)를 허용하세요"
    echo "  안내문: loadtest/contest/사람-참가-안내.md"
    ;;
  restore)
    login
    call DELETE /api/v1/admin/auth-relax > /dev/null
    echo "가입·로그인 한도 해제를 끝냈습니다 (평소 한도)"
    if [[ -f "$STATE" ]]; then   # RAISE_GLOBAL=yes로 올렸던 전체 한도 (또는 예전 방식으로 연 대회)
      orig=$(cat "$STATE")
      set_policy "$orig"
      rm -f "$STATE"
      echo "전체 가입 한도 ${KEY}를 ${orig}로 되돌렸습니다"
    fi
    ;;
  *)
    echo "사용법: $0 start | restore"; exit 1 ;;
esac
