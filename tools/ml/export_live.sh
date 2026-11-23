#!/usr/bin/env bash
# 실제 서버의 행동 로그·회원을 생성기와 같은 모양(events.jsonl · members.jsonl)으로 꺼낸다.
# 대회 데이터(시험용)를 features.py에 넣을 때 쓴다. docker compose가 떠 있는 서버의 저장소 폴더에서 실행.
#
#   tools/ml/export_live.sh <실행 태그> <출력 폴더>
#   python3 -I tools/ml/features.py <출력 폴더>
#
# 라벨은 이메일(lt{태그}.{종류}.g{묶음}.{번호}@…)에서 읽는다.
#   대회: bota~botd → RESELLER, human(연습 사람 변형 human…도) → NORMAL / 시나리오 12: normal → NORMAL, multi → RESELLER,
#   cancel → CANCEL_ABUSER, 나머지 → MACRO (eval_risk.sh와 같은 규칙)
# 카드 연결은 Oracle payment_method.method_hash에서 (행동 로그에는 카드가 없다).
# 비밀값은 컨테이너 안 환경 변수로만 쓴다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../.."

# 운영 모드: tools/ml/export_live.sh --recent <일수> <출력 폴더>
#   태그와 상관없이 최근 N일 행동 로그가 있는 모든 회원을 꺼낸다 (서버에서 모델 점수를 매길 때, deploy/model-refresh.sh).
#   정답이 없으므로 라벨은 모두 UNKNOWN. 이메일로 봇·사람을 가르지 않는다
RECENT=""
if [[ "${1:-}" == "--recent" ]]; then
  RECENT="${2:-}"; OUT="${3:-}"; TAG="recent"
  [[ "$RECENT" =~ ^[0-9]{1,3}$ && -n "$OUT" ]] || { echo "사용법: $0 --recent <일수> <출력 폴더>"; exit 1; }
else
  TAG="${1:-}"; OUT="${2:-}"
  if [[ ! "$TAG" =~ ^[a-z0-9]{1,8}$ || -z "$OUT" ]]; then
    echo "사용법: $0 <실행 태그> <출력 폴더>   또는   $0 --recent <일수> <출력 폴더>"; exit 1
  fi
fi
# 운영 모드는 부하 시험 계정(@loadtest.example.com, loadtest/lib/auth.js)을 뺀다. 서버에서 용량 시험을 돌리면 그 계정의 행동이
# "실제 사람"으로 섞여 사람 기준(recalibrate.py)과 재학습이 오염된다 (docs/강동균/features/용량-시험-안내.md)
if [[ -n "$RECENT" ]]; then MEMBER_WHERE="m.email NOT LIKE '%@loadtest.example.com'"; else MEMBER_WHERE="m.email LIKE 'lt${TAG}.%'"; fi
mkdir -p "$OUT"

sql() {
  docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
    bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"'
}

# ── 회원: member_id | 종류 | 묶음 | ci | 가입 일수 | 카드들(,) ─────────
# grep은 0건이면 1을 돌려줘 pipefail로 스크립트가 말없이 끝난다 → `|| true` (0건은 아래 n == 0이 처리)
sql <<EOF | tr -d '\r' | { grep -E '^[0-9]+\|' || true; } > "$OUT/.members.txt"
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 2000 TRIMSPOOL ON
SELECT m.member_id || '|' || REGEXP_SUBSTR(m.email, '^lt[a-z0-9]+\.([a-z]+)\.', 1, 1, NULL, 1)
       || '|' || REGEXP_SUBSTR(m.email, '\.g([0-9]+)\.', 1, 1, NULL, 1)
       || '|' || m.ci_hash || '|' || TRUNC(SYSDATE - CAST(m.created_at AS DATE))
       || '|' || (SELECT LISTAGG(p.method_hash, ',') WITHIN GROUP (ORDER BY p.method_hash)
                    FROM payment_method p WHERE p.member_id = m.member_id AND p.method_hash IS NOT NULL)
  FROM member m WHERE ${MEMBER_WHERE};
EOF
n=$(wc -l < "$OUT/.members.txt")
if (( n == 0 )); then echo "태그 ${TAG}의 계정이 없습니다."; rm -f "$OUT/.members.txt"; exit 1; fi

python3 -I - "$OUT" "$TAG" "$RECENT" <<'PY'
import json, os, sys
out, tag, recent = sys.argv[1], sys.argv[2], sys.argv[3]
LABEL = {"human": "NORMAL", "normal": "NORMAL", "multi": "RESELLER", "cancel": "CANCEL_ABUSER"}
with open(os.path.join(out, ".members.txt"), encoding="utf-8") as f, \
        open(os.path.join(out, "members.jsonl"), "w", encoding="utf-8") as w:
    for line in f:
        mid, kind, grp, ci, age, cards = (line.rstrip("\n").split("|") + [""] * 6)[:6]
        label = "UNKNOWN" if recent else LABEL.get(kind, "RESELLER" if kind.startswith("bot") else "NORMAL" if kind.startswith("human") else "MACRO")
        w.write(json.dumps({"member_id": int(mid), "label": label, "strategy": kind, "group": int(grp or 0),
                            "ci": ci, "cards": [c for c in cards.split(",") if c],
                            "created_days_ago": int(age or 0), "source": f"live:{tag}"}) + "\n")
PY
ids=$(cut -d'|' -f1 "$OUT/.members.txt" | paste -sd, -)
rm -f "$OUT/.members.txt"

# ── 행동 로그 (Mongo) ────────────────────────────────────────────
# 운영 모드는 회원 ID 목록 대신 기간으로 거른다 (회원이 많으면 ID 목록이 명령줄 길이를 넘는다)
if [[ -n "$RECENT" ]]; then
  EVENT_FILTER="{ ts: { \$gte: new Date(Date.now() - ${RECENT} * 86400000) }, member_id: { \$ne: null } }"
else
  EVENT_FILTER="{ member_id: { \$in: [${ids}] } }"
fi
docker compose exec -T mongo bash -c \
  'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "$0"' "
db.behavior_log.find(${EVENT_FILTER}, { _id: 0, expire_at: 0, user_agent: 0 })
  .sort({ member_id: 1, ts: 1 })
  .forEach(d => { d.ts = d.ts.toISOString(); print(EJSON.stringify(d, null, 0, { relaxed: true })); })" \
  | tr -d '\r' | { grep '^{' || true; } > "$OUT/events.jsonl"
# ↑ 최근 행동 로그 0건도 정상 (model-refresh.sh가 "행동 로그 없음"으로 끝냄). || true가 없으면 11-20~23처럼 매시간 "꺼내기 실패"

# 운영 모드는 기간으로만 꺼내므로, 위에서 고른 회원(부하 시험 계정 제외)의 이벤트만 남긴다
if [[ -n "$RECENT" ]]; then
  python3 -I - "$OUT" <<'PY'
import json, os, sys
out = sys.argv[1]
keep = {json.loads(l)["member_id"] for l in open(os.path.join(out, "members.jsonl"), encoding="utf-8") if l.strip()}
src = os.path.join(out, "events.jsonl")
kept = dropped = 0
with open(src, encoding="utf-8") as f, open(src + ".tmp", "w", encoding="utf-8") as w:
    for line in f:
        if json.loads(line).get("member_id") in keep:
            w.write(line); kept += 1
        else:
            dropped += 1
os.replace(src + ".tmp", src)
if dropped:
    print(f"부하 시험 계정 등 회원 목록 밖 이벤트 {dropped}건 뺌 (남김 {kept})")
PY
fi

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
