#!/usr/bin/env bash
# 예매 쪽 데이터 정합성 점검 — 부하 시험 뒤(또는 운영 중 아무 때나) 돈이·좌석이·이벤트가 어긋난 곳이 없는지 본다.
#
#   loadtest/tools/check_booking.sh <운행 번호> [최근 몇 분, 기본 60]
#
# 저장소 루트에서 docker compose가 Oracle·MongoDB를 띄운 상태로 (서버·각자 PC 같음). 읽기만 한다.
# 결과: 항목마다 OK / FAIL (+ 건수). FAIL이 하나라도 있으면 종료 코드 1.
#
#   1 결제했는데 표 없음      payment APPROVED인데 예약이 CONFIRMED가 아님 (돈만 빠져나감 — 가장 나쁨)
#   2 표 있는데 결제 없음     예약 CONFIRMED인데 APPROVED 결제가 없음
#   3 좌석·예약 상태 어긋남   SOLD 좌석의 예약이 CONFIRMED가 아님 / HOLD 좌석의 예약이 HOLD·PAYMENT_PENDING이 아님
#   4 확정 예약 좌석 빠짐     CONFIRMED 예약의 좌석 수(seat_count) ≠ SOLD로 잡힌 좌석 수
#   5 안 풀린 선점            만료 시각이 1분 넘게 지난 HOLD 좌석 (만료 스캐너가 밀림)
#   6 발행 안 된 이벤트       outbox PENDING·FAILED (예약 이벤트가 Kafka로 못 나감)
#   7 예매 완료 알림 빠짐     30초 넘게 지난 CONFIRMED 예약에 CONFIRMED 알림이 없음 (outbox → Kafka → 알림 소비자 → DB 끝까지)
#   8 행동 로그 빠짐          성공한 좌석 선점·결제 요청 수(MongoDB behavior_log) < Oracle의 예약·승인 결제 수 (봇 탐지 재료 유실)
#
# 2·4는 결제 직후 몇 초는 처리 중일 수 있어 30초 지난 것만 센다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../.."
RUN="${1:?운행 번호 (예: 9001)}"
MIN="${2:-60}"
[[ "$RUN" =~ ^[0-9]+$ && "$MIN" =~ ^[0-9]+$ ]] || { echo "숫자만"; exit 2; }

sql() {
  docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
    bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"'
}

W="r.run_id = ${RUN} AND r.held_at >= SYSTIMESTAMP - NUMTODSINTERVAL(${MIN}, 'MINUTE')"
OLD="NVL(r.confirmed_at, r.held_at) < SYSTIMESTAMP - NUMTODSINTERVAL(30, 'SECOND')"
out=$(sql <<EOF | tr -d '\r' | grep -E '^[a-z0-9_]+\|'
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 300 TRIMSPOOL ON
SELECT 'held|' || COUNT(*) FROM reservation r WHERE ${W};
SELECT 'confirmed|' || COUNT(*) FROM reservation r WHERE ${W} AND r.status = 'CONFIRMED';
SELECT 'approved|' || COUNT(*) FROM payment p JOIN reservation r ON r.reservation_id = p.reservation_id WHERE ${W} AND p.status = 'APPROVED';
SELECT 'approvedall|' || COUNT(*) FROM payment p WHERE p.status = 'APPROVED' AND p.approved_at >= SYSTIMESTAMP - NUMTODSINTERVAL(${MIN}, 'MINUTE');
SELECT 'c1|' || COUNT(*) FROM payment p JOIN reservation r ON r.reservation_id = p.reservation_id
 WHERE ${W} AND p.status = 'APPROVED' AND r.status <> 'CONFIRMED';
SELECT 'c2|' || COUNT(*) FROM reservation r WHERE ${W} AND r.status = 'CONFIRMED' AND ${OLD}
   AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.reservation_id = r.reservation_id AND p.status = 'APPROVED');
SELECT 'c3a|' || COUNT(*) FROM seat_inventory s JOIN reservation r ON r.reservation_id = s.reservation_id
 WHERE s.run_id = ${RUN} AND s.status = 'SOLD' AND r.status <> 'CONFIRMED';
SELECT 'c3b|' || COUNT(*) FROM seat_inventory s JOIN reservation r ON r.reservation_id = s.reservation_id
 WHERE s.run_id = ${RUN} AND s.status = 'HOLD' AND r.status NOT IN ('HOLD', 'PAYMENT_PENDING');
SELECT 'c4|' || COUNT(*) FROM reservation r WHERE ${W} AND r.status = 'CONFIRMED' AND ${OLD}
   AND r.seat_count <> (SELECT COUNT(DISTINCT s.seat_id) FROM seat_inventory s
                         WHERE s.reservation_id = r.reservation_id AND s.status = 'SOLD');
SELECT 'c5|' || COUNT(*) FROM seat_inventory s
 WHERE s.run_id = ${RUN} AND s.status = 'HOLD' AND s.hold_expires_at < SYSTIMESTAMP - NUMTODSINTERVAL(60, 'SECOND');
SELECT 'c6|' || COUNT(*) FROM outbox_event o WHERE o.status IN ('PENDING', 'FAILED')
   AND o.created_at < SYSTIMESTAMP - NUMTODSINTERVAL(30, 'SECOND');
SELECT 'c7|' || COUNT(*) FROM reservation r WHERE ${W} AND r.status = 'CONFIRMED' AND ${OLD}
   AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.ref_type = 'RESERVATION' AND n.ref_id = r.reservation_id AND n.type = 'CONFIRMED');
EOF
)
val() { awk -F'|' -v k="$1" '$1 == k {print $2}' <<<"$out"; }

# 행동 로그 (MongoDB): 성공한(2xx) 좌석 선점·결제 요청 수
mongo=$(docker compose exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "$0"' "
const since = new Date(Date.now() - ${MIN} * 60000);
const n = (t) => db.behavior_log.countDocuments({ run_id: ${RUN}, event_type: t, response_status: { \$gte: 200, \$lt: 300 }, ts: { \$gte: since } });
// 결제 로그는 run_id가 없다 (결제 주소에 운행이 없음 — 예약 번호는 meta.reservationId). 그래서 기간으로만 센다
const pay = db.behavior_log.countDocuments({ event_type: 'PAY', response_status: { \$gte: 200, \$lt: 300 }, ts: { \$gte: since } });
print('bhold|' + n('SEAT_HOLD')); print('bpay|' + pay);" | tr -d '\r' | grep -E '^b[a-z]+\|' || true)
out="${out}"$'\n'"${mongo}"

fail=0
row() { # 이름 건수 설명
  if [[ -z "$2" ]]; then printf '  ?    %s: 값을 못 읽음\n' "$1"; fail=1
  elif (( $2 == 0 )); then printf '  OK   %s\n' "$1"
  else printf '  FAIL %s: %s건 — %s\n' "$1" "$2" "$3"; fail=1; fi
}
echo "예매 정합성 점검: 운행 ${RUN}, 최근 ${MIN}분 (예약 $(val held) · 확정 $(val confirmed) · 승인 결제 $(val approved))"
row "1 결제했는데 표 없음" "$(val c1)" "돈은 빠졌는데 예약이 확정되지 않음"
row "2 표 있는데 결제 없음" "$(val c2)" "확정 예약에 승인 결제가 없음"
row "3 SOLD 좌석·예약 어긋남" "$(val c3a)" "판매 좌석의 예약이 확정 상태가 아님"
row "3 HOLD 좌석·예약 어긋남" "$(val c3b)" "선점 좌석의 예약이 선점·결제 중이 아님"
row "4 확정 예약 좌석 빠짐" "$(val c4)" "좌석 수와 SOLD 좌석이 다름"
row "5 안 풀린 선점" "$(val c5)" "만료 1분 넘게 지난 HOLD (만료 스캐너 밀림)"
row "6 발행 안 된 이벤트" "$(val c6)" "outbox PENDING·FAILED 30초 넘음"
row "7 예매 완료 알림 빠짐" "$(val c7)" "확정 예약에 CONFIRMED 알림 없음 (이벤트 전달·소비 확인)"
bh=$(val bhold); bp=$(val bpay); held=$(val held); appr=$(val approvedall)
if [[ -n "$bh" && -n "$held" ]]; then row "8 행동 로그 빠짐 (선점)" "$(( held > bh ? held - bh : 0 ))" "예약 ${held}건인데 성공한 선점 로그 ${bh}건"; fi
if [[ -n "$bp" && -n "$appr" ]]; then row "8 행동 로그 빠짐 (결제)" "$(( appr > bp ? appr - bp : 0 ))" "최근 ${MIN}분 승인 결제(전 운행) ${appr}건인데 성공한 결제 로그 ${bp}건"; fi
exit $fail
