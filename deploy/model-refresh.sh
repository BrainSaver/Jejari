#!/usr/bin/env bash
# 공용 서버: 최근 행동 로그로 위험 점수 모델(보조 의견)을 다시 매겨 MongoDB model_score에 넣는다.
# jejari-model.timer가 매시간 실행 (수동: sudo systemctl start jejari-model)
#
#   1) tools/ml/export_live.sh --recent N  → build/ml/recent (최근 N일 행동 로그가 있는 회원 전부, 라벨 없음)
#   2) 컨테이너(jejari-ml, deploy/ml/Dockerfile)에서 features.py(단계 특징까지) → 버전마다 score.py (GRU 포함)
#   3) tools/ml/load_scores.sh → model_score (버전·계정마다 1건, 같은 버전은 덮어씀)
#
# 버전 목록은 DB(model_run, ENSEMBLE)에서: 사용 중 + 꺼진 후보 최근 3개 (deploy/model-lib.sh). 주간 재학습
# (model-retrain.sh)이 등록한 후보도 여기서 같이 채점되어, 관리자가 A-09(모델 관리)에서 비교하고 켠다.
# 사용 중인 ENSEMBLE이 하나도 없을 때만(첫 실행) 맨 앞(ens-2026.11-01) 버전을 켠다.
#
# 모델 점수는 관리자에게 보여 주는 보조 의견뿐이다. 대기열 순번·이용 제한·케이스 열기에는 쓰지 않는다 (RISK-05).
# 먼저 필요: DB에 v25 패치(db/patch/v25.sql — model_run의 ens-2026.11-01 행), 이미지 빌드(아래 자동)
set -uo pipefail
cd "$(dirname "$0")/.."
source deploy/model-lib.sh
DAYS="${MODEL_DAYS:-1}"
FIRST_VERSION=ens-2026.11-01   # 사용 중인 버전이 없을 때(처음) 켤 버전
OUT=build/ml/recent

push() { # $1 = 1(성공) / 0(실패), $2 = 점수를 매긴 계정 수
  curl -fsS -m 5 -X PUT --data-binary @- "http://127.0.0.1:9091/metrics/job/jejari_model" >/dev/null 2>&1 <<M || true
# TYPE jejari_model_last_run_timestamp_seconds gauge
jejari_model_last_run_timestamp_seconds $(date +%s)
# TYPE jejari_model_last_success gauge
jejari_model_last_success $1
# TYPE jejari_model_scored_accounts gauge
jejari_model_scored_accounts ${2:-0}
M
}
fail() { echo "$1"; push 0; exit 1; }

# 0) 이미지 (Dockerfile이 바뀌었을 때만 다시 빌드 — 캐시)
docker build -q -t jejari-ml deploy/ml >/dev/null || fail "jejari-ml 이미지 빌드 실패"

# 1) 꺼내기
rm -rf "$OUT"
out=$(bash tools/ml/export_live.sh --recent "$DAYS" "$OUT" 2>&1) || { [[ "$out" == *"계정이 없습니다"* ]] && { echo "최근 ${DAYS}일 회원 없음"; push 1 0; exit 0; }; fail "꺼내기 실패: $out"; }
echo "$out" | head -1
[[ -s "$OUT/events.jsonl" ]] || { echo "최근 ${DAYS}일 행동 로그 없음"; push 1 0; exit 0; }

# CPU 1개까지만 (앱 1대·CPU 4개 서버라 매시간 점수 계산이 예매와 CPU를 다투지 않게). 컨테이너 안 작업이라
# systemd 유닛의 CPUQuota·Nice는 닿지 않는다 → docker run에서 제한. 바꾸려면 ML_CPUS
ml() { docker run --rm --cpus "${ML_CPUS:-1}" --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD:/w" jejari-ml bash -c "$1"; }

# 2) 특징 (한 번, 단계 특징까지 — 점수는 모델마다 자기가 쓰는 칸만 이름으로 꺼낸다)
ml "ML_STAGE=1 python -I tools/ml/features.py $OUT" > /tmp/jejari-model.log 2>&1 \
  || { tail -5 /tmp/jejari-model.log; fail "특징 만들기 실패: /tmp/jejari-model.log"; }

# 사용 중인 ENSEMBLE이 없으면(처음) FIRST_VERSION을 켠다. 그 뒤로는 관리자가 A-09에서 고른다
VERSIONS=$(model_versions 3)
[[ -n "$VERSIONS" ]] || fail "model_run에 ENSEMBLE 버전이 없습니다 (db/patch/v25.sql·v31.sql)"
grep -q ' Y ' <<<"$VERSIONS" && active=1 || active=0

total=0
while read -r ver dir _ _; do
  [[ -f "$dir/model.pkl" ]] || { echo "건너뜀: $ver ($dir/model.pkl 없음)"; continue; }
  # 3) 점수 — 버전마다 따로 폴더 (score.py가 features.csv 옆에 scores.csv를 쓰고, GRU는 옆의 events.jsonl을 읽는다)
  w="$OUT/$ver"; mkdir -p "$w"; cp "$OUT"/features.csv "$OUT"/events.jsonl "$OUT"/members.jsonl "$w"/; cp "$OUT"/runs.jsonl "$w"/ 2>/dev/null || true
  ml "python tools/ml/score.py $dir/model.pkl $w/features.csv" > "/tmp/jejari-model-$ver.log" 2>&1 \
    || { tail -5 "/tmp/jejari-model-$ver.log"; fail "점수 매기기 실패 ($ver): /tmp/jejari-model-$ver.log"; }
  grep -q "GRU" "/tmp/jejari-model-$ver.log" || echo "주의: $ver GRU 없이 매김 (torch·events.jsonl 확인)"
  act=(); [[ "$active" == "0" && "$ver" == "$FIRST_VERSION" ]] && act=(--activate)
  # 4) 적재
  bash tools/ml/load_scores.sh "$w/scores.csv" "$ver" --source "live:recent${DAYS}d" "${act[@]}" \
    > "/tmp/jejari-model-load-$ver.log" 2>&1 || { tail -5 "/tmp/jejari-model-load-$ver.log"; fail "적재 실패 ($ver): /tmp/jejari-model-load-$ver.log"; }
  n=$(($(wc -l < "$w/scores.csv") - 1)); total=$((total + n))
  echo "모델 점수 갱신: ${n}개 계정 · ${ver}${act:+ (처음이라 사용 중으로)}"
done <<<"$VERSIONS"
push 1 "$total"
