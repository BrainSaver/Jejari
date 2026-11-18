#!/usr/bin/env bash
# 실제 서버의 행동 로그·회원을 생성기와 같은 모양(events.jsonl · members.jsonl)으로 꺼낸다.
# 대회 데이터(시험용)를 features.py에 넣을 때 쓴다. docker compose가 떠 있는 서버의 저장소 폴더에서 실행.
#
#   tools/ml/export_live.sh <실행 태그> <출력 폴더>
#   python3 -I tools/ml/features.py <출력 폴더>
#
# 라벨은 이메일(lt{태그}.{종류}.g{묶음}.{번호}@…)에서 읽는다.
#   대회: bota~botd → RESELLER, human → NORMAL / 시나리오 12: normal → NORMAL, multi → RESELLER,
#   cancel → CANCEL_ABUSER, 나머지 → MACRO (eval_risk.sh와 같은 규칙)
# 카드 연결은 Oracle payment_method.method_hash에서 (행동 로그에는 카드가 없다).
# 비밀값은 컨테이너 안 환경 변수로만 쓴다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../.."

TAG="${1:-}"; OUT="${2:-}"
if [[ ! "$TAG" =~ ^[a-z0-9]{1,8}$ || -z "$OUT" ]]; then
  echo "사용법: $0 <실행 태그> <출력 폴더>"; exit 1
fi
mkdir -p "$OUT"

sql() {
  docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
    bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"'
}

# ── 회원: member_id | 종류 | 묶음 | ci | 가입 일수 | 카드들(,) ─────────
sql <<EOF | tr -d '\r' | grep -E '^[0-9]+\|' > "$OUT/.members.txt"
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 2000 TRIMSPOOL ON
SELECT m.member_id || '|' || REGEXP_SUBSTR(m.email, '^lt[a-z0-9]+\.([a-z]+)\.', 1, 1, NULL, 1)
       || '|' || REGEXP_SUBSTR(m.email, '\.g([0-9]+)\.', 1, 1, NULL, 1)
       || '|' || m.ci_hash || '|' || TRUNC(SYSDATE - CAST(m.created_at AS DATE))
       || '|' || (SELECT LISTAGG(p.method_hash, ',') WITHIN GROUP (ORDER BY p.method_hash)
                    FROM payment_method p WHERE p.member_id = m.member_id AND p.method_hash IS NOT NULL)
  FROM member m WHERE m.email LIKE 'lt${TAG}.%';
EOF
n=$(wc -l < "$OUT/.members.txt")
if (( n == 0 )); then echo "태그 ${TAG}의 계정이 없습니다."; rm -f "$OUT/.members.txt"; exit 1; fi

python3 -I - "$OUT" "$TAG" <<'PY'
import json, os, sys
out, tag = sys.argv[1], sys.argv[2]
LABEL = {"human": "NORMAL", "normal": "NORMAL", "multi": "RESELLER", "cancel": "CANCEL_ABUSER"}
with open(os.path.join(out, ".members.txt")) as f, open(os.path.join(out, "members.jsonl"), "w") as w:
    for line in f:
        mid, kind, grp, ci, age, cards = (line.rstrip("\n").split("|") + [""] * 6)[:6]
        label = LABEL.get(kind, "RESELLER" if kind.startswith("bot") else "MACRO")
        w.write(json.dumps({"member_id": int(mid), "label": label, "strategy": kind, "group": int(grp or 0),
                            "ci": ci, "cards": [c for c in cards.split(",") if c],
                            "created_days_ago": int(age or 0), "source": f"live:{tag}"}) + "\n")
PY
ids=$(cut -d'|' -f1 "$OUT/.members.txt" | paste -sd, -)
rm -f "$OUT/.members.txt"

# ── 행동 로그 (Mongo) ────────────────────────────────────────────
docker compose exec -T mongo bash -c \
  'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "$0"' "
db.behavior_log.find({ member_id: { \$in: [${ids}] } }, { _id: 0, expire_at: 0, user_agent: 0 })
  .sort({ member_id: 1, ts: 1 })
  .forEach(d => { d.ts = d.ts.toISOString(); print(EJSON.stringify(d, null, 0, { relaxed: true })); })" \
  | tr -d '\r' | grep '^{' > "$OUT/events.jsonl"

# ── 운행의 실제 판매 오픈 시각 (features.py가 "오픈 뒤 첫 선점" 기준으로 쓴다) ──────
runs=$(python3 -I -c "import json,sys; print(','.join(sorted({str(json.loads(l)['run_id']) for l in open(sys.argv[1]) if json.loads(l).get('run_id') is not None})))" "$OUT/events.jsonl")
: > "$OUT/runs.jsonl"
if [[ -n "$runs" ]]; then
  sql <<EOF | tr -d '\r' | grep -E '^[0-9]+\|' | while IFS='|' read -r rid opened; do
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 200
SELECT run_id || '|' || TO_CHAR(SYS_EXTRACT_UTC(FROM_TZ(CAST(sale_open_at AS TIMESTAMP), 'Asia/Seoul')), 'YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"')
  FROM train_run WHERE run_id IN (${runs});
EOF
    echo "{\"run_id\": ${rid}, \"open_ts\": \"${opened}\"}" >> "$OUT/runs.jsonl"
  done
fi

echo "계정 ${n} · 이벤트 $(wc -l < "$OUT/events.jsonl") · 운행 $(wc -l < "$OUT/runs.jsonl") → ${OUT}"
echo "다음: python3 -I tools/ml/features.py ${OUT}"
