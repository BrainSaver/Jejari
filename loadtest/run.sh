#!/usr/bin/env bash
# 사용법: ./run.sh <시나리오 번호|이름> [k6 추가 인자...]
#   예) ./run.sh 2 -e RACE_VUS=1000 -e LOCK_STRATEGY=redis
#       PROM=1 ./run.sh 1 -e PEAK_RATE=2000     # k6 지표를 Prometheus(Grafana)로도 전송
set -euo pipefail
cd "$(dirname "$0")"

declare -A MAP=(
  [1]=01_open_spike [open]=01_open_spike
  [2]=02_same_seat_race [race]=02_same_seat_race
  [3]=03_payment_failure [payment]=03_payment_failure
  [4]=04_idempotency [idem]=04_idempotency
  [5]=05_cancel_waitlist [waitlist]=05_cancel_waitlist
  [6]=06_token_bypass [bypass]=06_token_bypass
  [7]=07_bot_simulation [bot]=07_bot_simulation
  [8]=08_entry_burst [entry]=08_entry_burst
  [9]=09_penalty_order [penalty]=09_penalty_order
  [10]=10_auth_oracle [auth]=10_auth_oracle
  [11]=11_user_journey [journey]=11_user_journey
  [12]=12_bot_detection [botdetect]=12_bot_detection
  [13]=13_capacity [capacity]=13_capacity
  [14]=14_fault_demo [fault]=14_fault_demo
  [15]=15_pg_slow [pgslow]=15_pg_slow
)

key="${1:-}"
if [[ -z "$key" || -z "${MAP[$key]:-}" ]]; then
  echo "사용법: $0 <1-15|open|race|payment|idem|waitlist|bypass|bot|entry|penalty|auth|journey|botdetect|capacity|fault|pgslow> [k6 인자...]"
  exit 1
fi
shift

name="${MAP[$key]}"
mkdir -p results
stamp="$(date +%Y%m%d_%H%M%S)"
args=(run "scenarios/${name}.js" --summary-export "results/${name}_${stamp}.json")

if [[ "${PROM:-0}" == "1" ]]; then
  export K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}"
  export K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(95),p(99),avg,max}"
  args+=(-o experimental-prometheus-rw --tag "testid=${name}_${stamp}")
fi

exec k6 "${args[@]}" "$@"
