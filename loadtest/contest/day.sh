#!/usr/bin/env bash
# 봇 대회 날, 집계자 PC에서 돌리는 준비·정리 스크립트.
#
#   ADMIN_ID=… ADMIN_PW=… loadtest/contest/day.sh start     # 가입 제한 올림 → 대회 운행 만들기 → 접속 주소 안내
#   ADMIN_ID=… ADMIN_PW=… loadtest/contest/day.sh restore   # 대회가 끝나면 가입 제한을 원래 값으로
#
# 왜 가입 제한을 올리나: 가입(본인인증 포함)은 IP당 1시간에 10번 시도까지다 (app_policy auth.signup_rate_per_hour).
# 봇 네 개가 한 PC에서 명의 24개를 만들면 막힌다. 대회 동안만 정책 최댓값 100으로 올리고, 끝나면 되돌린다.
# 위험 점수 규칙·정책은 건드리지 않는다 (1차 대회 동안 수비 고정).
set -euo pipefail
cd "$(dirname "$0")/../.."

CMD="${1:-}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
STATE=".contest-day"   # 원래 정책 값을 적어 두는 파일 (gitignore)
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

case "$CMD" in
  start)
    login
    before=$(call GET "/api/v1/admin/policies/${KEY}" | val value)
    [[ -f "$STATE" ]] || echo "$before" > "$STATE"
    echo "가입 제한 ${KEY}: ${before} → 100 (대회 동안만. 끝나면 day.sh restore)"
    set_policy 100

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
    [[ -f "$STATE" ]] || { echo "되돌릴 값이 없습니다 (${STATE} 없음)"; exit 1; }
    orig=$(cat "$STATE")
    set_policy "$orig"
    rm -f "$STATE"
    echo "가입 제한 ${KEY}를 ${orig}로 되돌렸습니다"
    ;;
  *)
    echo "사용법: $0 start | restore"; exit 1 ;;
esac
