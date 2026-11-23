#!/usr/bin/env bash
# 공용 서버에 실제 KTX 시간표(오늘 기준 앞뒤 7일)를 넣는다. 매일 새벽 jejari-tago.timer가 실행 (수동: sudo systemctl start jejari-tago)
#
# 왜: 시드 운행은 "넣은 날 + 3일" 하루뿐이라 오늘·내일로 검색하면 열차가 없다 (11-18 팀원 제보).
#     tools/load-tago-schedule.js(길동현 님)가 공공데이터포털 TAGO API로 실제 시간표를 받아 MERGE SQL을 만든다 → 매일 다시 넣으면 늘 앞뒤 7일이 채워짐
# 필요: .env의 DATA_GO_KR_KEY_DEC (공공데이터포털 서비스 키, 디코딩 값). 출력하지 않음
# 서버에 Node를 설치하지 않고 Node 22 컨테이너로 실행. 결과는 Pushgateway에 보고 → 하루 넘게 실패하면 Grafana 경보
set -uo pipefail
cd "$(dirname "$0")/.."

push() { # $1 = 1(성공) / 0(실패), $2 = 넣은 운행 수
  curl -fsS -m 5 -X PUT --data-binary @- "http://127.0.0.1:9091/metrics/job/jejari_tago" >/dev/null 2>&1 <<EOF || true
# TYPE jejari_tago_last_run_timestamp_seconds gauge
jejari_tago_last_run_timestamp_seconds $(date +%s)
# TYPE jejari_tago_last_success gauge
jejari_tago_last_success $1
# TYPE jejari_tago_runs gauge
jejari_tago_runs ${2:-0}
EOF
}

# 키 이름: DATA_GO_KR_KEY_DEC(도구 기본), 없으면 DATA_GO_KR_KEY (요즘 공공데이터포털 키는 64자 하나라 인코딩·디코딩 구분 없음)
KEY=$(grep -E '^DATA_GO_KR_KEY_DEC=' .env 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r')
[[ -n "$KEY" ]] || KEY=$(grep -E '^DATA_GO_KR_KEY=' .env 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r')
if [[ -z "$KEY" ]]; then
  echo ".env에 DATA_GO_KR_KEY_DEC(또는 DATA_GO_KR_KEY)가 없습니다 (공공데이터포털 서비스 키)."; push 0; exit 1
fi

# 1) 시간표 받기 → build/tago/schedule.sql
# 키는 값 없이 -e 이름만 넘김 → docker가 이 프로세스 환경에서 읽음 (명령줄·ps에 키가 안 보이게)
export DATA_GO_KR_KEY_DEC="$KEY"; unset KEY
if ! docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e DATA_GO_KR_KEY_DEC \
     -v "$PWD:/w" -w /w node:22-alpine node tools/load-tago-schedule.js > /tmp/jejari-tago.log 2>&1; then
  echo "시간표 받기 실패: /tmp/jejari-tago.log"; tail -5 /tmp/jejari-tago.log; push 0; exit 1
fi
unset DATA_GO_KR_KEY_DEC
[[ -s build/tago/schedule.sql ]] || { echo "schedule.sql이 비어 있습니다."; push 0; exit 1; }

# 2) Oracle에 넣기 (도구 안내대로 컨테이너 안 관리자 계정. SQL이 FREEPDB1·jejari 스키마로 바꿔 MERGE)
out=$(docker exec -i jejari-oracle-1 bash -c 'sqlplus -s / as sysdba' < build/tago/schedule.sql 2>&1)
if grep -qE 'ORA-[0-9]+|SP2-[0-9]+' <<<"$out"; then
  echo "넣기 실패:"; grep -E 'ORA-[0-9]+|SP2-[0-9]+' <<<"$out" | sort | uniq -c | head -5; push 0; exit 1
fi

# 3) 확인: 앞으로 7일 안의 판매 중 TAGO 운행 수 (run_id 400000~999999)
n=$(docker exec -i jejari-oracle-1 bash -c 'sqlplus -s / as sysdba' <<'SQL' | tr -d ' \r' | grep -E '^[0-9]+$' | head -1
SET HEADING OFF FEEDBACK OFF PAGESIZE 0
ALTER SESSION SET CONTAINER = FREEPDB1;
SELECT COUNT(*) FROM jejari.train_run WHERE run_id BETWEEN 400000 AND 999999 AND status = 'ON_SALE' AND run_date BETWEEN TRUNC(SYSDATE) AND TRUNC(SYSDATE) + 7;
SQL
)
echo "실제 시간표 반영 완료: 앞으로 7일 판매 중 운행 ${n:-?}건"
push 1 "${n:-0}"
