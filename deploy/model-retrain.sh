#!/usr/bin/env bash
# 공용 서버: 주간 재학습 + 자동 시험 (③). jejari-model-retrain.timer가 일요일 새벽 실행 (수동: sudo systemctl start jejari-model-retrain)
#
#   1) 최근 14일 실데이터(export_live.sh --recent 14)와 관리자 판정(export_labels.sh)
#   2) 생성 데이터 시드 7·8·9 + 시험 시드 99 (지금 사용 중인 모델과 같은 생성기 설정)
#   3) tools/ml/retrain.py: 판정이 충분하면 다시 학습 → 사람 기준 갱신 → 지금 모델과 자동 시험 (gate.json)
#   4) 통과하면 꺼진 후보로 model_run에 등록 (params.dir = build/ml/models/<버전>). 켜는 건 관리자가 A-09에서
#      → 매시간 작업(model-refresh.sh)이 후보도 같이 채점하므로 켜기 전에 실데이터로 비교할 수 있다
#
# 학습은 CPU를 많이 쓴다. 판매가 없는 새벽에만. 사용 중인 모델이 없으면 아무것도 하지 않는다.
set -uo pipefail
cd "$(dirname "$0")/.."
source deploy/model-lib.sh
DAYS="${RETRAIN_DAYS:-14}"
W=build/ml/weekly
VER="ens-w$(date +%y%m%d)"
OUT="build/ml/models/$VER"
fail() { echo "$1"; model_push jejari_model_retrain jejari_model_retrain_last_run_timestamp_seconds "$(date +%s)" jejari_model_retrain_last_success 0; exit 1; }

docker build -q -t jejari-ml deploy/ml >/dev/null || fail "jejari-ml 이미지 빌드 실패"
read -r BASE_VER BASE_DIR _ FLOOR < <(model_versions 0 | head -1)
[[ -n "${BASE_VER:-}" ]] || { echo "사용 중인 모델이 없어 건너뜀 (A-09에서 먼저 켜세요)"; exit 0; }
[[ -f "$BASE_DIR/model.pkl" ]] || fail "기준 모델 파일 없음: $BASE_DIR"
echo "기준: $BASE_VER ($BASE_DIR) · 생성기 사람 고르기 하한 $FLOOR"

rm -rf "$W" && mkdir -p "$W"
out=$(bash tools/ml/export_live.sh --recent "$DAYS" "$W/real" 2>&1) || fail "실데이터 꺼내기 실패: $out"
bash tools/ml/export_labels.sh "$W/labels.jsonl" || fail "판정 꺼내기 실패"

# CPU 2개까지만 (일요일 새벽이라 1개보다 넉넉히, 그래도 앱 몫은 남김). 유닛의 Nice는 컨테이너에 닿지 않는다. 바꾸려면 ML_CPUS
ml() { docker run --rm --cpus "${ML_CPUS:-2}" --user "$(id -u):$(id -g)" -e HOME=/tmp -e ML_PICK_FLOOR="$FLOOR" -v "$PWD:/w" jejari-ml bash -c "$1"; }
ml "set -e
for s in 7 8 9; do python -I tools/ml/generate.py --members 6000 --seed \$s --out $W/g\$s >/dev/null; done
python -I tools/ml/generate.py --members 3000 --seed 99 --out $W/g99 >/dev/null
for d in g7 g8 g9 g99 real; do ML_STAGE=1 python -I tools/ml/features.py $W/\$d >/dev/null; done
python tools/ml/retrain.py --base $BASE_DIR --real $W/real --labels $W/labels.jsonl \
  --gen $W/g7 $W/g8 $W/g9 --gen-test $W/g99 --out $OUT" > /tmp/jejari-model-retrain.log 2>&1 \
  || { tail -20 /tmp/jejari-model-retrain.log; rm -rf "$OUT"; fail "재학습 실패: /tmp/jejari-model-retrain.log"; }
cat /tmp/jejari-model-retrain.log | tail -8

# gate.json → 등록할 값 (python 대신 컨테이너 밖 python3 -I: 표준 라이브러리만)
read -r PASSED THR PREC REC F1 SUMMARY < <(python3 -I - "$OUT/gate.json" "$OUT/model.pkl" <<'PY'
import json, sys
g = json.load(open(sys.argv[1], encoding="utf-8"))
thr = g["calibration"]["threshold"]["after"]
m = g["metrics"]
f = lambda v: "NULL" if v is None else f"{v:.3f}"
print("Y" if g["passed"] else "N", thr, f(m["precision"]), f(m["recall"]), f(m["f1"]), g["summary"].replace("'", ""))
PY
)
if [[ "$PASSED" != "Y" ]]; then
  echo "자동 시험 탈락 — 등록하지 않음 ($SUMMARY). 기록: $OUT/gate.json"
  model_push jejari_model_retrain jejari_model_retrain_last_run_timestamp_seconds "$(date +%s)" jejari_model_retrain_last_success 1 jejari_model_retrain_last_passed 0
  exit 0
fi

PARAMS=$(python3 -I - "$OUT/gate.json" "$OUT" "$BASE_VER" "$FLOOR" <<'PY'
import json, sys
g = json.load(open(sys.argv[1], encoding="utf-8"))
p = {"dir": sys.argv[2], "base": sys.argv[3], "mode": g["mode"], "stage": g["stage"],
     "gate": {"passed": g["passed"], "summary": g["summary"], "checks": g["checks"], "labels": g["labels"]}}
if sys.argv[4] == "1":
    p["pick_floor_s"] = 1.0
print(json.dumps(p, ensure_ascii=False).replace("'", "''"))
PY
)
model_sql <<SQL || fail "model_run 등록 실패"
SET DEFINE OFF
MERGE INTO model_run m USING (SELECT '$VER' v FROM dual) s ON (m.model_version = s.v)
WHEN NOT MATCHED THEN INSERT (model_version, algorithm, params, train_source, trained_at, threshold, precision_score, recall_score, f1_score, is_active)
VALUES ('$VER', 'ENSEMBLE', '$PARAMS', SUBSTR('주간 재학습: $SUMMARY', 1, 100), SYSTIMESTAMP, $THR, $PREC, $REC, $F1, 'N');
COMMIT;
SQL
echo "후보 등록: $VER (꺼짐) — 다음 매시간 채점 뒤 A-09에서 비교해 켜세요"
model_push jejari_model_retrain jejari_model_retrain_last_run_timestamp_seconds "$(date +%s)" jejari_model_retrain_last_success 1 jejari_model_retrain_last_passed 1
