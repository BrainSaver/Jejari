#!/usr/bin/env bash
# 위험 점수 1분 배치(RISK-02)·의심 케이스(RISK-04)를 손으로 확인하는 스크립트 (강다인)
#
# 준비: docker compose up -d → 다른 터미널에서 ./gradlew bootRun (앱이 http://localhost:8080 에 떠 있어야 함)
# 실행: bash tools/risk-demo.sh bot      # 0.1초 간격으로 좌석 배치도 30번 → 점수·케이스가 생겨야 함
#       bash tools/risk-demo.sh normal   # 3초 간격으로 3번 → 점수 행이 생기지 않아야 함
#       RESET_RATE=1 bash tools/risk-demo.sh bot   # 로컬에서 여러 번 돌릴 때: 본인인증 횟수 제한(IP당 시간당 10번) 기록을 지우고 시작
#
# 하는 일: 가짜 회원 가입·로그인 → 대기열 입장 → 좌석 배치도 요청 → 1분 배치를 기다림 → Oracle·Redis 결과 출력
# 가짜 이름·번호만 쓴다. 비밀값은 .env에서 읽고 화면에 찍지 않는다.
# 이름은 영문: Windows Git Bash의 curl은 한글 인자를 UTF-8로 안 보내 400이 난다.
set -euo pipefail

MODE="${1:-bot}"
BASE="${BASE:-http://localhost:8080}"
RUN_ID="${RUN_ID:-9001}"
cd "$(dirname "$0")/.."

envval() { grep -E "^$1=" .env | head -1 | cut -d= -f2- | tr -d '\r'; }
ORA_USER="$(envval ORACLE_APP_USER)"; ORA_USER="${ORA_USER:-jejari}"
ORA_PW="$(envval ORACLE_APP_PASSWORD)"
REDIS_PW="$(envval REDIS_PASSWORD)"

sql() { # 결과만 출력
  docker exec -i jejari-oracle-1 bash -c "sqlplus -s '$ORA_USER/$ORA_PW@//localhost:1521/FREEPDB1'" <<EOF
SET PAGESIZE 100 LINESIZE 250 FEEDBACK OFF HEADING ON TRIMSPOOL ON
COLUMN reasons FORMAT A120
COLUMN trigger_detail FORMAT A60
COLUMN label FORMAT A40
$1
EXIT
EOF
}
redis() { docker exec jejari-redis-1 redis-cli ${REDIS_PW:+-a "$REDIS_PW" --no-auth-warning} "$@"; }
json() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

curl -sf "$BASE/actuator/health" >/dev/null || { echo "앱이 안 떠 있어요. ./gradlew bootRun 먼저 실행하세요"; exit 1; }

if [ "${RESET_RATE:-}" = "1" ]; then # 로컬 Redis 전용. 운영 Redis에 쓰지 말 것
  for k in $(redis --scan --pattern "rate:signup:*"); do redis DEL "$k" >/dev/null; done
  echo "(로컬 본인인증 횟수 제한 기록을 지움)"
fi

# 1) 가짜 회원 만들기 (번호는 매번 무작위 → 매번 새 CI)
N=$(( (RANDOM << 15 | RANDOM) % 100000000 ))
PHONE=$(printf "010%08d" "$N")
EMAIL="risk-demo-$N@example.com"
PW="Demo-pass-$N!x"
echo "== 1. 가입·로그인 ($EMAIL)"
# 실패하면 서버 응답(code·message)을 보여 주고 멈춘다
call() { # call 방법 경로 [본문] → 응답 본문 출력. 2xx가 아니면 이유를 화면(stderr)에 쓰고 실패
  local body code
  body=$(mktemp)
  code=$(curl -s -o "$body" -w "%{http_code}" -X "$1" "$BASE$2" -H "Content-Type: application/json"     ${TOKEN:+-H "Authorization: Bearer $TOKEN"} ${3:+-d "$3"})
  if [[ "$code" != 2* ]]; then
    echo "   실패: $1 $2 → HTTP $code $(cat "$body")" >&2
    grep -q RATE_LIMITED "$body" && echo "   본인인증·가입은 IP당 시간당 10번 제한이 있어요 (보안 기능). 로컬이면 RESET_RATE=1 을 앞에 붙여 다시 실행하세요." >&2
    rm -f "$body"; return 1
  fi
  cat "$body"; rm -f "$body"
}
TOKEN=""
VT=$(call POST /api/v1/auth/verify "{\"name\":\"Demo\",\"phone\":\"$PHONE\",\"provider\":\"MOCK_PASS\"}" | json verificationToken)
[ -n "$VT" ] || exit 1
call POST /api/v1/auth/signup "{\"verificationToken\":\"$VT\",\"email\":\"$EMAIL\",\"password\":\"$PW\"}" >/dev/null || exit 1
TOKEN=$(call POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" | json accessToken)
[ -n "$TOKEN" ] || { echo "로그인 실패"; exit 1; }

# 2) 대기열 입장 → 입장 토큰 (좌석 배치도는 입장 토큰이 있어야 열림)
echo "== 2. 대기열 입장 (운행 $RUN_ID)"
call POST "/api/v1/runs/$RUN_ID/queue" >/dev/null || exit 1
PASS=""
for i in $(seq 30); do
  PASS=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/runs/$RUN_ID/queue/me" | json passToken || true)
  [ -n "$PASS" ] && break
  sleep 1
done
[ -n "$PASS" ] || { echo "30초 안에 입장하지 못함 (대기열 입장 스케줄러가 꺼져 있나요?)"; exit 1; }
H_AUTH="Authorization: Bearer $TOKEN"
H_PASS="X-Queue-Token: $PASS"

# 3) 좌석 배치도 요청 (행동 로그 VIEW_SEAT)
if [ "$MODE" = "bot" ]; then
  # curl을 따로 띄우면 Windows에서 한 번에 0.2초 넘게 걸려 "빠른 클릭"이 안 된다 → 한 curl로 30번 연속 요청
  echo "== 3. 봇처럼: 쉬지 않고 좌석 배치도 30번"
  ARGS=()
  for i in $(seq 30); do ARGS+=(-o /dev/null -H "$H_AUTH" -H "$H_PASS" "$BASE/api/v1/runs/$RUN_ID/seats"); [ "$i" -lt 30 ] && ARGS+=(--next); done
  curl -s "${ARGS[@]}"
else
  echo "== 3. 사람처럼: 3초 간격으로 좌석 배치도 3번"
  for i in 1 2 3; do curl -s -o /dev/null -H "$H_AUTH" -H "$H_PASS" "$BASE/api/v1/runs/$RUN_ID/seats"; sleep 3; done
fi

CI=$(sql "SET HEADING OFF
SELECT ci_hash FROM member WHERE email = '$EMAIL';" | tr -d ' \r\n')
echo "   CI 해시: ${CI:0:12}…"

# 4) 행동 로그 적재(Kafka → Mongo) + 1분 배치를 기다림
echo "== 4. 1분 배치 기다리는 중 (최대 90초)"
for i in $(seq 18); do
  sleep 5
  CNT=$(sql "SET HEADING OFF
SELECT COUNT(*) FROM risk_score WHERE subject_type = 'CI' AND subject_hash = '$CI';" | tr -d ' \r\n')
  [ "$CNT" = "1" ] && break
  printf "."
done
echo

# 5) 결과
echo "== 5. 결과"
echo "-- risk_score (CI)"
sql "SELECT final_score, rule_score, review_status, reasons FROM risk_score WHERE subject_type = 'CI' AND subject_hash = '$CI';"
echo "-- suspicious_case / case_marker"
sql "SELECT c.case_id, c.trigger_type, c.status, c.trigger_detail, k.rule_code, k.label
       FROM suspicious_case c LEFT JOIN case_marker k ON k.case_id = c.case_id
      WHERE c.subject_hash = '$CI';"
echo "-- Redis 캐시 risk:CI:… (대기열이 읽는 값)"
redis GET "risk:CI:$CI"
echo
if [ "$MODE" = "bot" ]; then
  echo "기대: final_score 30 이상 (FAST_CLICK 25 + 방금 가입한 계정 NEW_ACCOUNT 5, 간격이 아주 고르면 REGULAR_INTERVAL +15)"
  echo "      review_status PENDING, 케이스 RULE·OPEN, 마커 FAST_CLICK \"200ms 미만 간격 요청 N회\", Redis 값 = final_score"
  echo "      (케이스가 없으면 ERD 반영 전 DB라서 케이스 생성을 건너뛴 것 → 앱 로그에 '의심 케이스 테이블 준비 전')"
else
  echo "기대: final_score 5 (방금 가입한 계정이라 NEW_ACCOUNT 5점만), FAST_CLICK 없음, 케이스 없음, review_status NONE"
  echo "      (가입 7일이 지난 계정이면 0점이라 행 자체가 생기지 않음)"
fi
