# 모델 작업 공용 (deploy/model-refresh.sh · model-retrain.sh가 source). 저장소 루트에서 실행된다고 가정.
#
# 버전 목록은 DB(model_run, algorithm = ENSEMBLE)가 원본이다. 모델 폴더는 params JSON의 "dir".
# 예전 행(ens-2026.11-01)은 dir이 없어 data/ml/model로 본다.

export COMPOSE_FILE="${COMPOSE_FILE:-compose.yaml:deploy/compose.server.yaml}"

model_sql() {
  local op; op=$(grep '^ORACLE_APP_PASSWORD=' .env | cut -d= -f2-)
  docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle bash -lc "sqlplus -S -L jejari/$op@localhost:1521/FREEPDB1"
}

# 출력: 한 줄에 "버전 폴더 사용중(Y/N) 하한(0/1)" — 사용 중 먼저, 그다음 최근 학습 순. $1 = 꺼진 버전 최대 수 (기본 3)
model_versions() {
  local max_off="${1:-3}"
  printf "SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 2000 TRIMSPOOL ON LONG 4000\nSELECT model_version || '|' || is_active || '|' || REPLACE(DBMS_LOB.SUBSTR(params, 1500, 1), CHR(10), ' ') FROM model_run WHERE algorithm = 'ENSEMBLE' ORDER BY CASE is_active WHEN 'Y' THEN 0 ELSE 1 END, trained_at DESC;\n" \
    | model_sql | tr -d '\r' | grep -E '^[A-Za-z0-9._-]+\|[YN]\|' | {
      local off=0
      while IFS='|' read -r ver act params; do
        dir=$(sed -n 's/.*"dir" *: *"\([^"]*\)".*/\1/p' <<<"$params")
        [[ -z "$dir" && "$ver" == "ens-2026.11-01" ]] && dir=data/ml/model
        [[ -n "$dir" ]] || continue
        floor=0; grep -q '"pick_floor_s"' <<<"$params" && floor=1
        if [[ "$act" == "N" ]]; then off=$((off + 1)); (( off > max_off )) && continue; fi
        echo "$ver $dir $act $floor"
      done
    }
}

model_push() { # $1 = job 이름, 나머지 = "이름 값" 쌍
  local job="$1"; shift
  { while [ $# -gt 1 ]; do echo "# TYPE $1 gauge"; echo "$1 $2"; shift 2; done; } \
    | curl -fsS -m 5 -X PUT --data-binary @- "http://127.0.0.1:9091/metrics/job/$job" >/dev/null 2>&1 || true
}
