#!/usr/bin/env bash
# 실제 서비스처럼 섞은 시험의 "결과가 맞나" 리포트 (정합성 · 공정성 · 취소표 · 단체). 탐지·오탐은 eval_contest.sh가 본다.
#
#   loadtest/tools/eval_mixed.sh <태그> <운행 ID>        (docker compose가 떠 있는 곳에서, 읽기만 함)
#
# 라벨은 이메일에서 읽는다: lt{태그}.{종류}.g{묶음}.{번호}@…  (연습 봇 실행기 run.mjs와 같은 규칙)
#   human… = 사람, bot… = 봇. 섞는 법: loadtest/contest/practice/README.md "실제 서비스처럼 섞기"
#
# 정합성 (모두 0이어야 함)
#   1. 같은 좌석이 두 예약에 확정됨 (이중 판매)
#   2. 결제는 승인됐는데 예약이 확정·환불 상태가 아님 (돈만 빠짐)
#   3. 예약은 확정인데 승인된 결제가 없음 (돈 안 내고 표)
#   4. 한 명의(CI)가 이 운행에서 운행당 한도(LIMIT_PER_RUN, 기본 4)를 넘겨 확정 — 단체(GROUP)는 대표 명의라 뺌
#   5. 결제 기한이 GRACE_MIN분 넘게 지났는데 아직 HOLD·PAYMENT_PENDING (정리 안 됨)
#   6. 확정 좌석 수 + 남은 HOLD 좌석 수가 Redis 좌석 키 수와 다름 (DB·Redis 어긋남, 참고용)
# 공정성: 종류별 명의 수 · 표 받은 명의 비율 · 확정 좌석 · 사람/봇 좌석 몫
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../.."
TAG="${1:-}"; RUN="${2:-}"
if [[ ! "$TAG" =~ ^[a-z0-9]{1,8}$ || ! "$RUN" =~ ^[0-9]+$ ]]; then
  echo "사용법: $0 <태그(영문 소문자·숫자)> <운행 ID>"; exit 1
fi
LIMIT_PER_RUN="${LIMIT_PER_RUN:-4}"
GRACE_MIN="${GRACE_MIN:-3}"
sql() {
  docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
    bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"'
}
LAB="SELECT member_id, ci_hash, REGEXP_SUBSTR(email, '^lt[a-z0-9]+\\.([a-z]+)\\.', 1, 1, NULL, 1) AS kind
       FROM member WHERE email LIKE 'lt${TAG}.%'"

echo "== 운행 ${RUN} · 태그 ${TAG} =="
sql <<EOF
SET LINES 200 PAGES 200 FEEDBACK OFF
COL check_name FORMAT A60
COL n FORMAT 999999
PROMPT
PROMPT [정합성] 모두 0이어야 합니다 (운행 전체, 태그와 상관없이)
SELECT '1 이중 판매 (같은 좌석 확정 2건 이상)' check_name, COUNT(*) n FROM (
  SELECT rs.seat_id FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.reservation_id
   WHERE r.run_id = ${RUN} AND r.status = 'CONFIRMED' GROUP BY rs.seat_id HAVING COUNT(*) > 1)
UNION ALL
SELECT '2 결제 승인인데 예약이 확정 아님 (돈만 빠짐)', COUNT(*) FROM payment p JOIN reservation r ON r.reservation_id = p.reservation_id
   WHERE r.run_id = ${RUN} AND p.status = 'APPROVED' AND r.status <> 'CONFIRMED'
UNION ALL
SELECT '3 확정인데 승인 결제 없음 (돈 안 내고 표)', COUNT(*) FROM reservation r
   WHERE r.run_id = ${RUN} AND r.status = 'CONFIRMED'
     AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.reservation_id = r.reservation_id AND p.status = 'APPROVED')
UNION ALL
SELECT '4 운행당 한도 ${LIMIT_PER_RUN}매 초과 명의 (단체 제외)', COUNT(*) FROM (
  SELECT r.ci_hash FROM reservation r WHERE r.run_id = ${RUN} AND r.status = 'CONFIRMED' AND r.source <> 'GROUP'
   GROUP BY r.ci_hash HAVING SUM(r.seat_count) > ${LIMIT_PER_RUN})
UNION ALL
SELECT '5 기한 ${GRACE_MIN}분 지났는데 HOLD·결제중으로 남음', COUNT(*) FROM reservation r
   WHERE r.run_id = ${RUN} AND r.status IN ('HOLD', 'PAYMENT_PENDING')
     AND r.pay_deadline_at < SYSTIMESTAMP - NUMTODSINTERVAL(${GRACE_MIN}, 'MINUTE');

PROMPT
PROMPT [공정성] 종류별 (표받음 = 확정 예약이 1건 이상인 명의)
COL kind FORMAT A16
SELECT l.kind, COUNT(DISTINCT l.member_id) accounts,
       COUNT(DISTINCT CASE WHEN r.status = 'CONFIRMED' THEN l.member_id END) got,
       ROUND(100 * COUNT(DISTINCT CASE WHEN r.status = 'CONFIRMED' THEN l.member_id END) / COUNT(DISTINCT l.member_id)) got_pct,
       NVL(SUM(CASE WHEN r.status = 'CONFIRMED' THEN r.seat_count END), 0) seats,
       NVL(SUM(CASE WHEN r.status = 'EXPIRED' THEN 1 END), 0) expired,
       NVL(SUM(CASE WHEN r.status = 'CANCELED' THEN 1 END), 0) canceled
  FROM (${LAB}) l LEFT JOIN reservation r ON r.member_id = l.member_id AND r.run_id = ${RUN}
 GROUP BY l.kind ORDER BY CASE WHEN l.kind LIKE 'human%' THEN 0 ELSE 1 END, l.kind;

PROMPT
PROMPT [공정성] 사람 vs 봇 — 확정 좌석 몫과 명의당 좌석
COL side FORMAT A6
SELECT side, accounts, seats, ROUND(100 * seats / NULLIF(SUM(seats) OVER (), 0)) seat_pct, ROUND(seats / accounts, 2) seats_per_acct FROM (
  SELECT CASE WHEN l.kind LIKE 'human%' THEN 'human' ELSE 'bot' END side, COUNT(DISTINCT l.member_id) accounts,
         NVL(SUM(CASE WHEN r.status = 'CONFIRMED' THEN r.seat_count END), 0) seats
    FROM (${LAB}) l LEFT JOIN reservation r ON r.member_id = l.member_id AND r.run_id = ${RUN}
   GROUP BY CASE WHEN l.kind LIKE 'human%' THEN 'human' ELSE 'bot' END);

PROMPT
PROMPT [운행] 좌석·예약 상태 (판매 좌석 = 확정 좌석)
SELECT status, COUNT(*) reservations, SUM(seat_count) seats, SUM(CASE WHEN source = 'WAITLIST' THEN seat_count ELSE 0 END) via_waitlist,
       SUM(CASE WHEN source = 'GROUP' THEN seat_count ELSE 0 END) via_group
  FROM reservation WHERE run_id = ${RUN} GROUP BY status ORDER BY status;

PROMPT
PROMPT [취소표 대기] 상태별 (ACCEPTED = 제안받고 결제)
SELECT w.status, COUNT(*) n, SUM(w.seat_count) seats FROM waitlist w WHERE w.run_id = ${RUN} GROUP BY w.status ORDER BY w.status;

PROMPT
PROMPT [단체] 상태별
SELECT g.status, COUNT(*) n, SUM(g.passenger_count) passengers FROM group_booking g WHERE g.run_id = ${RUN} GROUP BY g.status;
EOF

# 6. DB·Redis 좌석 수 대조 (참고): Redis 좌석 키 = 확정·선점·제안·할당석. 키 이름 규칙은 redis_keys 테이블 seat:hold
keys=$(docker compose exec -T redis redis-cli --scan --pattern "seat:hold:${RUN}:*" 2>/dev/null | tr -d '\r' | wc -l)
echo
echo "[참고] Redis 좌석 키 ${keys}개 (확정·선점·제안·할당석 합. 위 '운행' 표의 CONFIRMED+HOLD+PAYMENT_PENDING 좌석과 크게 다르면 어긋남)"
echo
echo "탐지·오탐: loadtest/tools/eval_contest.sh ${TAG} ${RUN}"
