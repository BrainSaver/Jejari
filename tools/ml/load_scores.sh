#!/usr/bin/env bash
# RISK-05 모델 보조 의견 적재: score.py가 낸 scores.csv → MongoDB model_score (계정마다 1건)
#
#   bash tools/ml/load_scores.sh <scores.csv> <model_version> [--source <태그>] [--activate]
#
#   - 정답 라벨(label·strategy)·표시(flag)는 넣지 않는다 (관리자 화면에 정답이 보이면 안 됨)
#   - 모델 이름을 ERD 이름으로: 부스팅 → GBM/gbm, IF → IF/if, GRU → GRU/gru
#   - (member_id, model_version)으로 덮어쓴다 → 같은 버전으로 다시 넣어도 한 건
#   - model_run에 그 버전(algorithm = ENSEMBLE)이 있어야 한다 (문턱·성능은 거기에). 없으면 멈춘다
#   - --activate: 그 버전을 사용 중으로 (ENSEMBLE은 하나만 Y) → 관리자 화면(A-06)에 나타남
#
# 예) 대회 데이터로 매긴 점수를 올리고 켜기:
#   python3 tools/ml/score.py data/ml/model/model.pkl data/ml/contest-c1/features.csv
#   bash tools/ml/load_scores.sh data/ml/contest-c1/scores.csv ens-2026.11-01 --source live:c1 --activate
set -euo pipefail
cd "$(dirname "$0")/../.."

CSV="${1:?scores.csv 경로}"; VERSION="${2:?model_version (예: ens-2026.11-01)}"; shift 2
SOURCE=""; ACTIVATE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="$2"; shift 2 ;;
    --activate) ACTIVATE=1; shift ;;
    *) echo "모르는 옵션: $1" >&2; exit 1 ;;
  esac
done
[[ "$VERSION" =~ ^[A-Za-z0-9._-]{1,20}$ ]] || { echo "model_version 형식이 틀렸습니다" >&2; exit 1; }
[[ -z "$SOURCE" || "$SOURCE" =~ ^[A-Za-z0-9._:-]{1,40}$ ]] || { echo "--source 형식이 틀렸습니다" >&2; exit 1; }

env_val() { grep "^$1=" .env | cut -d= -f2-; }
MU=$(env_val MONGO_APP_USER); MU=${MU:-jejari}; MP=$(env_val MONGO_APP_PASSWORD); OP=$(env_val ORACLE_APP_PASSWORD)
sql() { docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle bash -lc "sqlplus -S -L jejari/$OP@localhost:1521/FREEPDB1"; }

echo "[1/3] model_run 확인 ($VERSION)"
n=$(printf "SET HEADING OFF FEEDBACK OFF PAGESIZE 0\nSELECT COUNT(*) FROM model_run WHERE model_version = '%s' AND algorithm = 'ENSEMBLE';\n" "$VERSION" | sql | tr -d '[:space:]')
[ "$n" = "1" ] || { echo "model_run에 ENSEMBLE 버전 $VERSION 이 없습니다. 문턱·성능과 함께 먼저 넣어 주세요 (ERD 예시: ens-2026.11-01)" >&2; exit 1; }

echo "[2/3] scores.csv → JSON (정답 라벨 빼기)"
JSONL=$(mktemp)
node - "$CSV" "$VERSION" "$SOURCE" > "$JSONL" <<'JS'
const fs = require('fs');
const [file, version, source] = process.argv.slice(2);
// 따옴표가 있는 CSV 한 줄 나누기 (근거에 쉼표가 들어갈 수 있음)
function split(line) {
  const out = []; let cur = ''; let q = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (q) { if (c === '"' && line[i + 1] === '"') { cur += '"'; i++; } else if (c === '"') q = false; else cur += c; }
    else if (c === '"') q = true; else if (c === ',') { out.push(cur); cur = ''; } else cur += c;
  }
  out.push(cur); return out;
}
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter((l) => l.trim());
const head = split(lines[0]);
const BY = { '부스팅': 'GBM', IF: 'IF', GRU: 'GRU' };
const KEY = { '부스팅': 'gbm', IF: 'if', GRU: 'gru' };
const now = new Date().toISOString();
let ok = 0, skip = 0;
for (const line of lines.slice(1)) {
  const r = Object.fromEntries(split(line).map((v, i) => [head[i], v]));
  const id = Number(r.member_id);
  if (!Number.isSafeInteger(id) || id <= 0) { skip++; continue; } // 생성 데이터의 가짜 ID 등
  const scores = {};
  for (const h of head) if (h.startsWith('score_')) { const k = KEY[h.slice(6)]; if (k && r[h] !== '') scores[k] = Number(r[h]); }
  const doc = { member_id: id, model_version: version, score: Number(r.score), by: BY[r.by] || r.by, scores,
    reasons: (r.reasons || '').split(' · ').map((s) => s.trim()).filter(Boolean).slice(0, 3),
    scored_at: { $date: now } };
  if (source) doc.source = source;
  process.stdout.write(JSON.stringify(doc) + '\n'); ok++;
}
process.stderr.write(`  계정 ${ok}개 (건너뜀 ${skip})\n`);
JS

echo "[3/3] MongoDB model_score 적재"
docker compose exec -T mongo mongoimport --quiet --uri "mongodb://$MU:$MP@localhost:27017/jejari?authSource=jejari" \
  --collection model_score --mode upsert --upsertFields member_id,model_version < "$JSONL"
rm -f "$JSONL"
docker compose exec -T mongo mongosh --quiet "mongodb://$MU:$MP@localhost:27017/jejari?authSource=jejari" --eval "
  db.model_score.createIndex({member_id: 1, model_version: 1}, {name: 'ux_member_version', unique: true});
  print('  model_score ($VERSION): ' + db.model_score.countDocuments({model_version: '$VERSION'}) + '건');"

if [ "$ACTIVATE" = 1 ]; then
  printf "UPDATE model_run SET is_active = CASE WHEN model_version = '%s' THEN 'Y' ELSE 'N' END, updated_at = SYSTIMESTAMP WHERE algorithm = 'ENSEMBLE';\nCOMMIT;\n" "$VERSION" | sql >/dev/null
  echo "  $VERSION 을 사용 중으로 켰습니다 → 관리자 케이스 화면(A-06)에 모델 보조 의견이 보입니다"
fi
