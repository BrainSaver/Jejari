#!/usr/bin/env bash
# 봇 대회용 명절 행사 + 운행 하나를 관리자 API로 만들고 바로 판매를 연다.
#
# 사용법:
#   ADMIN_ID=… ADMIN_PW=… loadtest/contest/setup.sh [BASE_URL]
#   BASE_URL 기본값 http://localhost:8080  (공용 서버면 https://…)
#
# 환경 변수 (모두 선택):
#   TRAIN_ID  열차 (기본 1: 서울→부산 하행, 1,050석)
#   DAYS      오늘부터 며칠 뒤 운행 (기본 10. 시드 04가 쓰는 "오늘+3일"과 겹치지 않게)
#   NAME      행사 이름 (기본 "봇 대회 YYYYMMDD")
#
# 만드는 것:
#   - 명절 행사 1개: 기간 = 운행일 하루, 판매 오픈 = 지금. 매수 제한은 서버 기본값
#     (1회 4 · 열차당 4 · 1일 20 · 명절 8, 본인인증 CI 기준) — 대회 규칙과 같다
#   - 그 행사에 묶인 운행 1개 → 바로 ON_SALE
#
# 끝나면 운행 ID를 출력한다. 봇·평가 스크립트에 그 값을 넘긴다.
# 관리자 비밀번호는 명령줄이 아니라 환경 변수로만 받는다 (셸 기록에 남지 않게).
set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
TRAIN_ID="${TRAIN_ID:-1}"
DAYS="${DAYS:-10}"
: "${ADMIN_ID:?ADMIN_ID 환경 변수가 필요합니다 (SUPERVISOR 관리자)}"
: "${ADMIN_PW:?ADMIN_PW 환경 변수가 필요합니다}"

RUN_DATE=$(date -d "+${DAYS} days" +%F 2>/dev/null || date -v+"${DAYS}"d +%F)   # GNU / BSD(macOS)
NOW=$(date +%Y-%m-%dT%H:%M:%S)
NAME="${NAME:-봇 대회 $(date +%Y%m%d)}"

# JSON 한 값 꺼내기 (jq 없이). $1 = 키, 표준 입력 = 응답
val() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }

call() { # $1 메서드 $2 경로 $3 본문 → 응답 본문. 2xx가 아니면 멈춘다
  local out code
  out=$(curl -sS -w '\n%{http_code}' -X "$1" "${BASE_URL}$2" \
    -H 'Content-Type: application/json' ${TOKEN:+-H "Authorization: Bearer ${TOKEN}"} \
    ${3:+--data "$3"})
  code=${out##*$'\n'}; out=${out%$'\n'*}
  if [[ "$code" != 2* ]]; then echo "실패: $1 $2 → HTTP ${code} ${out}" >&2; exit 1; fi
  printf '%s' "$out"
}

echo "관리자 로그인 (${BASE_URL})"
TOKEN=$(call POST /api/v1/admin/auth/login "{\"loginId\":\"${ADMIN_ID}\",\"password\":\"${ADMIN_PW}\"}" | val accessToken)
[[ -n "$TOKEN" ]] || { echo "토큰을 받지 못했습니다"; exit 1; }

echo "명절 행사 만들기: ${NAME} (${RUN_DATE})"
EVENT=$(call POST /api/v1/admin/sale-events \
  "{\"name\":\"${NAME}\",\"holidayFrom\":\"${RUN_DATE}\",\"holidayTo\":\"${RUN_DATE}\",\"saleOpenAt\":\"${NOW}\",\"status\":\"OPEN\"}")
EVENT_ID=$(printf '%s' "$EVENT" | val saleEventId)
echo "  행사 ${EVENT_ID} · 매수 제한 $(printf '%s' "$EVENT" | sed -n 's/.*"limits":\({[^}]*}\).*/\1/p')"

echo "운행 만들기: 열차 ${TRAIN_ID}, ${RUN_DATE}"
BULK=$(call POST /api/v1/admin/runs \
  "{\"trainId\":${TRAIN_ID},\"from\":\"${RUN_DATE}\",\"to\":\"${RUN_DATE}\",\"saleEventId\":${EVENT_ID}}")
RUN_ID=$(printf '%s' "$BULK" | sed -n 's/.*"created":\[\([0-9]*\).*/\1/p')
if [[ -z "$RUN_ID" ]]; then
  echo "운행이 만들어지지 않았습니다 (같은 날 운행이 이미 있으면 건너뜀): ${BULK}"; exit 1
fi

echo "판매 열기"
# 판매 오픈 시각이 "지금"이라 판매 오픈 스케줄러(10초 주기)가 먼저 열었을 수 있다 → 상태만 확인
STATUS=$(call GET "/api/v1/admin/runs/${RUN_ID}" "" | val status)
if [[ "$STATUS" == "SCHEDULED" ]]; then
  STATUS=$(call POST "/api/v1/admin/runs/${RUN_ID}/open" "{}" | val status)
fi
if [[ "$STATUS" != "ON_SALE" ]]; then echo "판매 중이 아닙니다 (${STATUS})"; exit 1; fi
echo "  ${STATUS}"

echo
echo "준비 끝. 대회 운행 ID = ${RUN_ID}  (행사 ${EVENT_ID}, ${RUN_DATE})"
echo "  봇:   RUN_ID=${RUN_ID} 로 실행"
echo "  평가: loadtest/tools/eval_contest.sh <태그> ${RUN_ID}"
