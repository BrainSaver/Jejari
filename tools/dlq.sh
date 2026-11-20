#!/usr/bin/env bash
# 격리 보관함(DLT) 보기 · 다시 넣기 — 소비자가 처리하지 못하고 옮겨 둔 메시지 (event/dlq/DeadLetters)
#
#   tools/dlq.sh list                          보관함 3개의 그룹·이유별 건수 + 최근 5건
#   tools/dlq.sh show <토픽> [건수]            그 보관함의 메시지 (헤더·값). 토픽: reservation · behavior · replay
#   tools/dlq.sh replay <토픽> <소비 그룹> [이유]  그 그룹 몫을 원래 토픽에 다시 넣는다 → 그 그룹만 다시 처리
#                                              이유를 안 주면 retries_exhausted·handler_failed만 (형식 오류 malformed는 코드를
#                                              고치기 전에는 다시 넣어도 또 보관함으로 오므로, 고친 뒤 이유에 malformed를 줘서 따로)
#
# 다시 넣기 전에 원인부터 고친다 (형식 오류면 코드, 재시도 소진이면 DB·Redis 장애 등). 다시 넣은 메시지에는
# 헤더 dlt-replay-for=<그룹>이 붙어 다른 소비 그룹은 건너뛴다. 소비자는 멱등(inbox·event_id 유니크)이라 두 번 넣어도 결과가 같다.
# 보관함은 30일 보관. 다시 넣어도 보관함에서 지우지는 않는다 (기록으로 남김) — 같은 것을 두 번 넣지 않게 replay 결과를 적어 둘 것.
#
# 저장소 루트에서 docker compose가 Kafka를 띄운 상태로 (서버·각자 PC 같음).
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

topic_of() {
  case "${1:-}" in
    reservation) echo jejari.reservation ;;
    behavior) echo jejari.behavior ;;
    replay) echo jejari.replay ;;
    *) echo "토픽은 reservation · behavior · replay 중 하나" >&2; exit 2 ;;
  esac
}

# 보관함 전체를 "헤더<US>키<US>값" 줄로 (US = 0x1f)
dump() {
  docker compose exec -T kafka bash -c '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic "$0" --from-beginning --timeout-ms 8000 --property print.headers=true --property print.key=true \
    --property key.separator=$(printf "\037") 2>/dev/null' "$1.DLT" || true
}

cmd="${1:-list}"
case "$cmd" in
  list)
    for t in reservation behavior replay; do
      src=$(topic_of "$t")
      lines=$(dump "$src")
      n=$(grep -c . <<<"$lines" || true)
      echo "== ${src}.DLT : ${n}건"
      (( n == 0 )) && continue
      # 그룹·이유별
      awk -F$'\037' '{ g=""; r=""; split($1, hs, ","); for (i in hs) { split(hs[i], kv, ":"); if (kv[1]=="dlt-group") g=kv[2]; if (kv[1]=="dlt-reason") r=kv[2] } c[g" · "r]++ }
        END { for (k in c) printf "   %-40s %d\n", k, c[k] }' <<<"$lines"
      echo "   최근:"
      tail -5 <<<"$lines" | awk -F$'\037' '{ printf "   - %s\n     %s\n", $1, substr($3, 1, 160) }'
    done
    ;;
  show)
    src=$(topic_of "${2:-}")
    dump "$src" | tail -"${3:-20}" | awk -F$'\037' '{ printf "[%d] 헤더 %s\n    키 %s\n    값 %s\n", NR, $1, $2, $3 }'
    ;;
  replay)
    src=$(topic_of "${2:-}")
    group="${3:?소비 그룹 (예: notification, seat-release, risk-signal, behavior-sink, replay-sink)}"
    [[ "$group" =~ ^[a-z-]+$ ]] || { echo "그룹 이름이 이상합니다: $group"; exit 2; }
    reason="${4:-}"
    [[ -z "$reason" || "$reason" =~ ^(malformed|retries_exhausted|handler_failed)$ ]] || { echo "이유: malformed · retries_exhausted · handler_failed"; exit 2; }
    picked=$(dump "$src" | awk -F$'\037' -v g="dlt-group:${group}," -v r="$reason" '
      index($1 ",", g) > 0 && (r == "" ? index($1, "dlt-reason:malformed") == 0 : index($1, "dlt-reason:" r) > 0) { print }')
    n=$(grep -c . <<<"$picked" || true)
    if (( n == 0 )); then echo "${src}.DLT에 ${group} 몫${reason:+ (${reason})}이 없습니다 (이유를 안 주면 형식 오류는 뺌)"; exit 0; fi
    echo "${src}.DLT의 ${group} 몫 ${n}건을 ${src}에 다시 넣습니다 (그 그룹만 처리)"
    # 원래 키·값 그대로 + 헤더 dlt-replay-for=<그룹>. 키가 같으면 같은 파티션 → 같은 예약의 순서 유지
    awk -F$'\037' -v g="$group" '{ printf "dlt-replay-for:%s\t%s\037%s\n", g, $2, $3 }' <<<"$picked" \
      | docker compose exec -T kafka bash -c '/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
          --topic "$0" --property parse.key=true --property parse.headers=true --property key.separator=$(printf "\037")' "$src"
    echo "완료: ${n}건. 처리됐는지는 A-10 데이터 놓침·밀림 / loadtest/tools/check_booking.sh로 확인"
    ;;
  *)
    sed -n '2,12p' "$0"; exit 2 ;;
esac
