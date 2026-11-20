#!/usr/bin/env bash
# 예매 쪽 데이터 정합성 점검 — 부하 시험 뒤(또는 운영 중 아무 때나) 돈이·좌석이·이벤트가 어긋난 곳이 없는지 본다.
#
#   loadtest/tools/check_booking.sh <운행 번호 | all> [최근 몇 분, 기본 60]
#
#   all = 모든 운행 (서버의 매시간 점검 deploy/integrity-check.sh가 씀).
#   PROM_OUT=<파일>을 주면 항목별 건수를 "이름 값" 줄로 그 파일에도 쓴다 (Pushgateway로 보내 Grafana 경보)
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
RUN="${1:?운행 번호 (예: 9001) 또는 all}"
MIN="${2:-60}"
[[ ( "$RUN" =~ ^[0-9]+$ || "$RUN" == all ) && "$MIN" =~ ^[0-9]+$ ]] || { echo "운행 번호(숫자) 또는 all, 분(숫자)"; exit 2; }
if [[ "$RUN" == all ]]; then RUNF="1 = 1"; SRUNF="1 = 1"; MRUN=""; LABEL="모든 운행"
else RUNF="r.run_id = ${RUN}"; SRUNF="s.run_id = ${RUN}"; MRUN="run_id: ${RUN}, "; LABEL="운행 ${RUN}"; fi

sql() {
  docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
    bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"'
}

W="${RUNF} AND r.held_at >= SYSTIMESTAMP - NUMTODSINTERVAL(${MIN}, 'MINUTE')"
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
 WHERE ${SRUNF} AND s.status = 'SOLD' AND r.status <> 'CONFIRMED';
SELECT 'c3b|' || COUNT(*) FROM seat_inventory s JOIN reservation r ON r.reservation_id = s.reservation_id
 WHERE ${SRUNF} AND s.status = 'HOLD' AND r.status NOT IN ('HOLD', 'PAYMENT_PENDING');
SELECT 'c4|' || COUNT(*) FROM reservation r WHERE ${W} AND r.status = 'CONFIRMED' AND ${OLD}
   AND r.seat_count <> (SELECT COUNT(DISTINCT s.seat_id) FROM seat_inventory s
                         WHERE s.reservation_id = r.reservation_id AND s.status = 'SOLD');
SELECT 'c5|' || COUNT(*) FROM seat_inventory s
 WHERE ${SRUNF} AND s.status = 'HOLD' AND s.hold_expires_at < SYSTIMESTAMP - NUMTODSINTERVAL(60, 'SECOND');
SELECT 'c6|' || COUNT(*) FROM outbox_event o WHERE o.status IN ('PENDING', 'FAILED')
   AND o.created_at < SYSTIMESTAMP - NUMTODSINTERVAL(30, 'SECOND');
-- 알림 CHECK가 CONFIRMED를 받는지 (ERD v30 패치 전이면 확정 알림을 만들지 않으므로 7번을 건너뜀)
SELECT 'c7ok|' || COUNT(*) FROM user_constraints WHERE table_name = 'NOTIFICATION' AND constraint_name = 'CK_NOTIFICATION_TYPE'
   AND search_condition_vc LIKE '%''CONFIRMED''%';
SELECT 'c7|' || COUNT(*) FROM reservation r WHERE ${W} AND r.status = 'CONFIRMED' AND ${OLD}
   AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.ref_type = 'RESERVATION' AND n.ref_id = r.reservation_id AND n.type = 'CONFIRMED');
EOF
)
val() { awk -F'|' -v k="$1" '$1 == k {print $2}' <<<"$out"; }

# 행동 로그 (MongoDB): 성공한(2xx) 좌석 선점·결제 요청 수
mongo=$(docker compose exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "$0"' "
const since = new Date(Date.now() - ${MIN} * 60000);
const n = (t) => db.behavior_log.countDocuments({ ${MRUN}event_type: t, response_status: { \$gte: 200, \$lt: 300 }, ts: { \$gte: since } });
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
echo "예매 정합성 점검: ${LABEL}, 최근 ${MIN}분 (예약 $(val held) · 확정 $(val confirmed) · 승인 결제 $(val approved))"
row "1 결제했는데 표 없음" "$(val c1)" "돈은 빠졌는데 예약이 확정되지 않음"
row "2 표 있는데 결제 없음" "$(val c2)" "확정 예약에 승인 결제가 없음"
row "3 SOLD 좌석·예약 어긋남" "$(val c3a)" "판매 좌석의 예약이 확정 상태가 아님"
row "3 HOLD 좌석·예약 어긋남" "$(val c3b)" "선점 좌석의 예약이 선점·결제 중이 아님"
row "4 확정 예약 좌석 빠짐" "$(val c4)" "좌석 수와 SOLD 좌석이 다름"
row "5 안 풀린 선점" "$(val c5)" "만료 1분 넘게 지난 HOLD (만료 스캐너 밀림)"
row "6 발행 안 된 이벤트" "$(val c6)" "outbox PENDING·FAILED 30초 넘음"
if [[ "$(val c7ok)" == 0 ]]; then
  printf '  -    7 예매 완료 알림 빠짐: 건너뜀 (알림 CHECK에 CONFIRMED가 없음 — ERD v30 패치 전)\n'
  c7v=""
else
  c7v="$(val c7)"
  row "7 예매 완료 알림 빠짐" "$c7v" "확정 예약에 CONFIRMED 알림 없음 (이벤트 전달·소비 확인)"
fi
bh=$(val bhold); bp=$(val bpay); held=$(val held); appr=$(val approvedall)
c8a=""; c8b=""
if [[ -n "$bh" && -n "$held" ]]; then c8a=$(( held > bh ? held - bh : 0 )); row "8 행동 로그 빠짐 (선점)" "$c8a" "예약 ${held}건인데 성공한 선점 로그 ${bh}건"; fi
if [[ -n "$bp" && -n "$appr" ]]; then c8b=$(( appr > bp ? appr - bp : 0 )); row "8 행동 로그 빠짐 (결제)" "$c8b" "최근 ${MIN}분 승인 결제(전 운행) ${appr}건인데 성공한 결제 로그 ${bp}건"; fi

# 기계용 출력 (deploy/integrity-check.sh → Pushgateway). 못 읽은 항목은 빼서 "값 없음"이 0으로 보이지 않게
if [[ -n "${PROM_OUT:-}" ]]; then
  {
    for kv in "paid_no_ticket:$(val c1)" "ticket_no_payment:$(val c2)" "sold_mismatch:$(val c3a)" "hold_mismatch:$(val c3b)" \
              "confirmed_missing_seats:$(val c4)" "stale_hold:$(val c5)" "outbox_unpublished:$(val c6)" \
              "missing_confirm_notification:${c7v}" "behavior_hold_missing:${c8a}" "behavior_pay_missing:${c8b}"; do
      [[ -n "${kv#*:}" ]] && echo "${kv%%:*} ${kv#*:}"
    done
    echo "checked_reservations $(val held)"
  } > "$PROM_OUT"
fi
exit $fail
