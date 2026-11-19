#!/usr/bin/env bash
# 공용 서버: 최근 행동 로그로 위험 점수 모델(보조 의견)을 다시 매겨 MongoDB model_score에 넣는다.
# jejari-model.timer가 매시간 실행 (수동: sudo systemctl start jejari-model)
#
#   1) tools/ml/export_live.sh --recent N  → build/ml/recent (최근 N일 행동 로그가 있는 회원 전부, 라벨 없음)
#   2) 컨테이너(jejari-ml, deploy/ml/Dockerfile)에서 features.py → score.py (data/ml/model, GRU 포함)
#   3) tools/ml/load_scores.sh → model_score (계정마다 1건, 같은 버전은 덮어씀) + 이 버전을 사용 중으로
#
# 모델 점수는 관리자에게 보여 주는 보조 의견뿐이다. 대기열 순번·이용 제한·케이스 열기에는 쓰지 않는다 (RISK-05).
# 먼저 필요: DB에 v25 패치(db/patch/v25.sql — model_run의 ens-2026.11-01 행), 이미지 빌드(아래 자동)
set -uo pipefail
cd "$(dirname "$0")/.."
export COMPOSE_FILE=compose.yaml:deploy/compose.server.yaml
DAYS="${MODEL_DAYS:-1}"
VERSION="${MODEL_VERSION:-ens-2026.11-01}"   # data/ml/model (11-18 학습, 문턱 97.6)과 짝인 model_run 행
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

# 2) 특징 → 점수 (컨테이너, 저장소를 /w에. 파일 주인은 이 사용자로)
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD:/w" jejari-ml bash -c \
  "python -I tools/ml/features.py $OUT >/dev/null && python tools/ml/score.py data/ml/model/model.pkl $OUT/features.csv" \
  > /tmp/jejari-model.log 2>&1 || { tail -5 /tmp/jejari-model.log; fail "점수 매기기 실패: /tmp/jejari-model.log"; }
grep -q "GRU" /tmp/jejari-model.log || echo "주의: GRU 없이 매김 (torch·events.jsonl 확인)"
n=$(($(wc -l < "$OUT/scores.csv") - 1))

# 3) 적재 + 사용 중으로
bash tools/ml/load_scores.sh "$OUT/scores.csv" "$VERSION" --source "live:recent${DAYS}d" --activate \
  > /tmp/jejari-model-load.log 2>&1 || { tail -5 /tmp/jejari-model-load.log; fail "적재 실패: /tmp/jejari-model-load.log"; }
echo "모델 점수 갱신: ${n}개 계정 · ${VERSION}"
push 1 "$n"
