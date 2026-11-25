#!/usr/bin/env bash
# 장애 주입 시연 — 예매 부하를 거는 도중에 Redis(또는 Kafka)를 죽였다가 살리고, 그동안 무슨 일이 있었는지 보여 준다.
#
#   loadtest/tools/fault_demo.sh [redis|kafka]        (기본 redis)
#
# 순서
#   1. k6 시나리오 14(정상 사용자 USERS명이 예매를 반복)를 띄운다. 요청마다 시각·상태 코드를 CSV로 남긴다
#   2. 부하가 FAULT_AT초 돈 뒤 대상 컨테이너를 죽인다 (MODE=kill: SIGKILL · flush: 데이터를 전부 지우고 SIGKILL)
#   3. FAULT_SEC초 뒤 다시 띄우고 healthy가 될 때까지 기다린다
#   4. 부하가 끝나면 40초 기다린 뒤(정합성 점검 2·4·7번은 30초 지난 것만 셈) 시간대별 분석 + check_booking.sh
#
# 보여 주는 것 (docs/강동균/features/장애-주입-시연.md)
#   - 장애 동안: 죽은 쪽을 쓰는 요청만 503(2초 안에)으로 거절, 열차 조회처럼 안 쓰는 요청은 그대로 성공
#   - 정합성: 돈만 빠진 결제·좌석·예약 어긋남·안 나간 이벤트가 0건 (check_booking.sh 10항목)
#   - 복구: 다시 띄운 뒤 몇 초 만에 성공률이 장애 전으로 돌아오나
#
# 환경 변수
#   USERS=30  TOTAL_SEC=240  FAULT_AT=60  FAULT_SEC=45  RUN_ID=9001  MODE=kill|flush  BASE_URL=http://localhost:8080
#   flush는 Redis만: 데이터를 전부 잃은 최악의 재시작 (T14·T26이 같은 일을 테스트로 증명)
#
# 준비: docker compose up -d · 앱을 신뢰 프록시 옵션으로 실행 (계정을 만들 때 요청마다 다른 IP)
#   ./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1"
# 같이 보면 좋은 화면: 관리자 A-10 트래픽 관제 (상태 띠가 빨강 → 초록으로 돌아오는 것)
#
# 공용 서버에는 쓰지 않는다 (docker compose kill을 이 PC의 컨테이너에 한다). 끝나거나 중간에 멈춰도 대상은 다시 띄운다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

TARGET="${1:-redis}"
[[ "$TARGET" == redis || "$TARGET" == kafka ]] || { echo "대상은 redis 또는 kafka"; exit 2; }
USERS="${USERS:-30}"; TOTAL="${TOTAL_SEC:-240}"; AT="${FAULT_AT:-60}"; DOWN="${FAULT_SEC:-45}"
RUN="${RUN_ID:-9001}"; MODE="${MODE:-kill}"; BASE="${BASE_URL:-http://localhost:8080}"
[[ "$MODE" == kill || "$MODE" == flush ]] || { echo "MODE는 kill 또는 flush"; exit 2; }
[[ "$MODE" == flush && "$TARGET" != redis ]] && { echo "MODE=flush는 redis만"; exit 2; }
(( AT + DOWN + 45 <= TOTAL )) || { echo "TOTAL_SEC(${TOTAL})는 FAULT_AT + FAULT_SEC + 45초 이상이어야 복구 뒤를 볼 수 있습니다"; exit 2; }
case "$BASE" in http://localhost*|http://127.0.0.1*) ;; *) echo "이 PC의 앱에만 씁니다 (BASE_URL=${BASE})"; exit 2;; esac

now_ms() { python -c "import time;print(int(time.time()*1000))"; }

# ---- 사전 점검
curl -sf "${BASE}/actuator/health" > /dev/null || { echo "앱(${BASE})이 응답하지 않습니다. 먼저 앱을 띄우세요"; exit 2; }
cid="$(docker compose ps -q "$TARGET")"
[[ -n "$cid" && "$(docker inspect -f '{{.State.Running}}' "$cid")" == true ]] || { echo "${TARGET} 컨테이너가 떠 있지 않습니다 (docker compose up -d)"; exit 2; }
command -v k6 >/dev/null || { echo "k6가 없습니다"; exit 2; }

stamp="$(date +%Y%m%d_%H%M%S)"
OUT="loadtest/results/fault_${TARGET}_${stamp}"
mkdir -p "$OUT"
EV="$OUT/events.tsv"
: > "$EV"
mark() { printf '%s\t%s\n' "$(now_ms)" "$1" >> "$EV"; echo "[$(date +%H:%M:%S)] $2"; }

K6PID=""
restore() {
  # 어떻게 끝나든 대상은 다시 떠 있게 (Ctrl+C 포함)
  docker compose start "$TARGET" >/dev/null 2>&1 || true
  [[ -n "$K6PID" ]] && kill "$K6PID" 2>/dev/null || true
}
trap restore EXIT

echo "장애 시연: ${TARGET} (${MODE}) · 정상 사용자 ${USERS}명 · 전체 ${TOTAL}초 중 ${AT}초에 죽이고 ${DOWN}초 뒤 살림 · 운행 ${RUN}"
echo "결과 폴더: ${OUT}"
(
  cd loadtest
  K6_CSV_TIME_FORMAT=unix_milli exec k6 run scenarios/14_fault_demo.js --quiet --system-tags status,name,scenario,method \
    --out "csv=../${OUT}/requests.csv" \
    -e USERS="$USERS" -e DURATION_SEC="$TOTAL" -e RUN_ID="$RUN" -e BASE_URL="$BASE"
) > "$OUT/k6.log" 2>&1 &
K6PID=$!

echo "[$(date +%H:%M:%S)] 계정 ${USERS}개 준비 중 (가입·로그인, 1~2분)…"
for _ in $(seq 1 1200); do
  grep -q FAULT_DEMO_LOAD_START "$OUT/k6.log" 2>/dev/null && break
  kill -0 "$K6PID" 2>/dev/null || { echo "k6가 준비 중에 끝났습니다:"; tail -20 "$OUT/k6.log"; exit 1; }
  sleep 1
done
grep -q FAULT_DEMO_LOAD_START "$OUT/k6.log" || { echo "준비가 20분 안에 끝나지 않았습니다"; exit 1; }
start_ms="$(grep -o 'FAULT_DEMO_LOAD_START [0-9]*' "$OUT/k6.log" | head -1 | awk '{print $2}')"
printf '%s\t%s\n' "$start_ms" load_start >> "$EV"
echo "[$(date +%H:%M:%S)] 부하 시작 — ${AT}초 동안 정상 상태를 잰다"

sleep "$AT"
if [[ "$MODE" == flush ]]; then
  docker compose exec -T redis redis-cli FLUSHALL >/dev/null
  mark fault_start "Redis 데이터를 전부 지우고(FLUSHALL) 프로세스를 죽임 (SIGKILL)"
else
  mark fault_start "${TARGET} 프로세스를 죽임 (SIGKILL)"
fi
docker compose kill "$TARGET" >/dev/null

sleep "$DOWN"
mark recover_start "${TARGET} 다시 띄움"
docker compose start "$TARGET" >/dev/null
cid="$(docker compose ps -q "$TARGET")"
for _ in $(seq 1 120); do
  h="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo starting)"
  [[ "$h" == healthy || "$h" == running ]] && break
  sleep 1
done
mark recovered "${TARGET} 정상(${h}) — 부하는 끝까지 계속"

wait "$K6PID" || true
K6PID=""
mark load_end "부하 끝"
tail -3 "$OUT/k6.log" | sed 's/^/  /'

echo "[$(date +%H:%M:%S)] 40초 기다린 뒤 정합성 점검 (결제 직후 처리 중인 건을 빼려고)…"
sleep 40

end_ms="$(now_ms)"
minutes=$(( (end_ms - start_ms) / 60000 + 2 ))
set +e
loadtest/tools/check_booking.sh "$RUN" "$minutes" > "$OUT/integrity.txt" 2>&1
integrity=$?
if (( integrity != 0 )); then
  # Kafka가 멈춘 동안 행동 로그·이벤트는 디스크·outbox에 쌓였다가 다시 보낸다. 늦게 도착한 것인지 진짜 유실인지 가르려고 한 번 더
  echo "[$(date +%H:%M:%S)] 정합성 점검에 FAIL이 있어 60초 뒤 다시 봅니다 (밀린 것을 따라잡는 중일 수 있음)…"
  mv "$OUT/integrity.txt" "$OUT/integrity_first.txt"
  sleep 60
  loadtest/tools/check_booking.sh "$RUN" "$(( minutes + 1 ))" > "$OUT/integrity.txt" 2>&1
  integrity=$?
fi
set -e

python -X utf8 loadtest/tools/fault_report.py "$OUT" "$TARGET" "$MODE" "$integrity"

# ---- 정리: 이 시연이 만든 예약·좌석을 지운다 (KEEP=1이면 남김)
# 남겨 두면 운행의 좌석이 계속 팔린 채라 다음 시연·통합 테스트(같은 운행 9001의 좌석을 씀)와 부딪힌다 (2026-11-25 실제로 19건 실패)
tag="$(grep -o '태그 [a-z0-9]*' "$OUT/k6.log" | head -1 | awk '{print $2}')"
if [[ "${KEEP:-0}" != 1 && -n "$tag" ]]; then
  docker compose exec -T oracle bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"' > "$OUT/cleanup.txt" 2>&1 <<SQL
WHENEVER SQLERROR EXIT ROLLBACK
CREATE GLOBAL TEMPORARY TABLE tmp_fault_r (rid NUMBER) ON COMMIT PRESERVE ROWS;
INSERT INTO tmp_fault_r SELECT r.reservation_id FROM reservation r JOIN member m ON m.member_id = r.member_id
 WHERE m.email LIKE 'lt${tag}x%@loadtest.example.com';
DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(rid) FROM tmp_fault_r);
DELETE FROM notification WHERE ref_type = 'RESERVATION' AND ref_id IN (SELECT rid FROM tmp_fault_r);
DELETE FROM cancellation WHERE reservation_id IN (SELECT rid FROM tmp_fault_r);
DELETE FROM payment WHERE reservation_id IN (SELECT rid FROM tmp_fault_r);
DELETE FROM reservation_history WHERE reservation_id IN (SELECT rid FROM tmp_fault_r);
DELETE FROM saga_instance WHERE reservation_id IN (SELECT rid FROM tmp_fault_r);
DELETE FROM reservation_seat WHERE reservation_id IN (SELECT rid FROM tmp_fault_r);
DELETE FROM seat_inventory WHERE reservation_id IN (SELECT rid FROM tmp_fault_r);
DELETE FROM reservation WHERE reservation_id IN (SELECT rid FROM tmp_fault_r);
DELETE FROM purchase_counter WHERE ci_hash IN (SELECT ci_hash FROM member WHERE email LIKE 'lt${tag}x%@loadtest.example.com');
COMMIT;
TRUNCATE TABLE tmp_fault_r;
DROP TABLE tmp_fault_r;
SQL
  # 앱 Redis(0번)의 그 운행 좌석 키·배치도 캐시도 (좌석 키 값이 지운 예약을 가리켜 계속 "판매됨"으로 보이지 않게)
  docker compose exec -T redis sh -c "for p in 'seat:hold:${RUN}:*' 'seatmap:${RUN}'; do redis-cli -n 0 --scan --pattern \"\$p\" | xargs -r redis-cli -n 0 DEL >/dev/null; done" || true
  echo "[$(date +%H:%M:%S)] 시연이 만든 예약·좌석을 지웠습니다 (회원 lt${tag}x… 은 남김, 남기려면 KEEP=1)"
fi
exit "$integrity"
