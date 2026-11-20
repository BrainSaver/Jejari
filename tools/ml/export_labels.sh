#!/usr/bin/env bash
# 관리자 판정을 학습용 정답으로 꺼낸다 → <출력 파일> (한 줄에 계정 하나, JSON)
#
#   tools/ml/export_labels.sh data/ml/labels.jsonl
#
# 정답 = 케이스 판정(suspicious_case.verdict)이 붙은 케이스에 묶인 계정(case_session.member_id).
#   RESELLER · MACRO · CANCEL_ABUSE → 봇,  NORMAL → 사람.  한 계정에 둘 다 있으면 봇.
#   audit = true: 무작위 점검 표본(A-09 "점검 표본 뽑기")으로 연 케이스의 판정. 규칙·모델이 고른 케이스가 아니라
#   치우침이 없는 정답이다 (자기 확신 고리 막기, docs/강동균/features/모델-비교-김성원-20261119.md "사용자가 많아질수록")
# 개인정보는 없다 (회원 번호·판정만).
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../.."
OUT="${1:?출력 파일 (예: data/ml/labels.jsonl)}"
mkdir -p "$(dirname "$OUT")"

docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
  bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"' <<'SQL' | tr -d '\r' | grep -E '^[0-9]+\|' > "$OUT.tmp"
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 400 TRIMSPOOL ON
SELECT cs.member_id || '|' || c.verdict || '|' ||
       CASE WHEN c.trigger_type = 'ADMIN' AND c.trigger_detail LIKE '무작위 점검%' THEN 'Y' ELSE 'N' END || '|' ||
       TO_CHAR(NVL(c.closed_at, c.updated_at), 'YYYY-MM-DD"T"HH24:MI:SS')
  FROM suspicious_case c JOIN case_session cs ON cs.case_id = c.case_id
 WHERE c.verdict IS NOT NULL;
SQL

python3 -I - "$OUT.tmp" "$OUT" <<'PY'
import json, sys
src, out = sys.argv[1], sys.argv[2]
LABEL = {"RESELLER": "RESELLER", "MACRO": "MACRO", "CANCEL_ABUSE": "CANCEL_ABUSER", "NORMAL": "NORMAL"}
by = {}
with open(src, encoding="utf-8") as f:
    for line in f:
        mid, verdict, audit, at = (line.rstrip("\n").split("|") + [""] * 4)[:4]
        m = int(mid); bot = verdict != "NORMAL"
        cur = by.get(m)
        if cur is None or (bot and not cur["is_bot"]):          # 봇 판정이 하나라도 있으면 봇
            by[m] = {"member_id": m, "label": LABEL.get(verdict, "MACRO"), "is_bot": bot,
                     "audit": audit == "Y", "decided_at": at}
        elif audit == "Y":
            cur["audit"] = True
with open(out, "w", encoding="utf-8") as w:
    for d in by.values():
        w.write(json.dumps(d, ensure_ascii=False) + "\n")
b = sum(d["is_bot"] for d in by.values()); a = sum(d["audit"] for d in by.values())
print(f"판정 {len(by)}명 (봇 {b} · 사람 {len(by) - b} · 무작위 점검 {a}) → {out}")
PY
rm -f "$OUT.tmp"
