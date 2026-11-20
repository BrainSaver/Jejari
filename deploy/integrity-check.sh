#!/usr/bin/env bash
# 매시간 예매 정합성 점검 (jejari-integrity.timer가 부름) → Pushgateway → Grafana 경보 → Discord
#
#   bash deploy/integrity-check.sh            (서버, 저장소 루트)
#
# loadtest/tools/check_booking.sh all 120 을 돌려 항목별 건수를 보낸다. 읽기만 한다.
#   jejari_integrity_fail{check="paid_no_ticket"} 0  … 10개 (못 읽은 항목은 빠짐)
#   jejari_integrity_checked_reservations           최근 2시간 예약 수 (참고)
#   jejari_integrity_last_run_timestamp_seconds     마지막 실행 (타이머가 멈추면 경보)
#   jejari_integrity_read_ok                        1 = Oracle·MongoDB에서 값을 읽음, 0 = 못 읽음 (경보)
# 창을 2시간으로 잡아 매시간 실행이 서로 겹친다 (한 번 놓쳐도 다음에 잡힘). 같은 어긋남은 고칠 때까지 매시간 다시 보고된다.
set -uo pipefail
cd "$(dirname "$0")/.."
. deploy/model-lib.sh   # COMPOSE_FILE (서버 compose 파일 묶음)
PUSH="${PUSHGATEWAY_URL:-http://127.0.0.1:9091}"

out=$(mktemp); log=$(mktemp)
PROM_OUT="$out" bash loadtest/tools/check_booking.sh all 120 >"$log" 2>&1
code=$?
cat "$log"

now=$(date +%s)
read_ok=0
[[ -s "$out" ]] && grep -q '^paid_no_ticket ' "$out" && read_ok=1
{
  echo "# TYPE jejari_integrity_fail gauge"
  grep -v '^checked_reservations ' "$out" 2>/dev/null | while read -r k v; do echo "jejari_integrity_fail{check=\"$k\"} $v"; done
  echo "# TYPE jejari_integrity_checked_reservations gauge"
  awk '$1 == "checked_reservations" {print "jejari_integrity_checked_reservations " $2}' "$out" 2>/dev/null
  echo "# TYPE jejari_integrity_read_ok gauge"
  echo "jejari_integrity_read_ok $read_ok"
  echo "# TYPE jejari_integrity_last_run_timestamp_seconds gauge"
  echo "jejari_integrity_last_run_timestamp_seconds $now"
} | curl -fsS -m 5 -X PUT --data-binary @- "$PUSH/metrics/job/jejari_integrity" >/dev/null 2>&1 \
  || echo "Pushgateway($PUSH)에 보내지 못함 — 경보 'jejari-integrity-stale'로 알려짐"
rm -f "$out" "$log"
# 어긋남이 있어도 서비스는 실패로 끝내지 않는다 (경보는 Grafana가 냄). 값을 못 읽었을 때만 실패
(( read_ok == 1 )) || exit 1
exit 0
