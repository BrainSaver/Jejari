#!/usr/bin/env bash
# 시나리오 12(봇 탐지) 평가: 정답 라벨(synthetic_label) ↔ 서버 판정(suspicious_case)
#
# 사용법 (저장소 어디서든):  loadtest/tools/eval_risk.sh <실행 태그>
#   실행 태그는 시나리오 12가 끝날 때 출력합니다 ("실행 태그 ab12 ...").
#
# 하는 일
#   1) 위험 점수 1분 배치가 이번 실행의 신호를 다 처리할 때까지 기다림 (Redis risk:dirty 가 빌 때까지)
#   2) 이번 실행 계정의 이메일(lt{tag}.{종류}.g{묶음}.{번호}@…)에서 정답을 읽어 synthetic_label에 MERGE
#   3) 회원마다 연결된 대상(CI · 결제 수단 · 기기)에 의심 케이스가 하나라도 열렸으면 "탐지"
#      기기 ↔ 회원 연결은 Mongo behavior_log에서 읽음 (member_device는 아직 채우는 곳이 없음)
#   4) 종류별 탐지 수, 탐지율(봇), 오탐률(정상), 걸린 규칙 표 출력. 목표: 탐지율 ≥ 90%, 오탐률 ≤ 5%
#
# 비밀값은 컨테이너 안의 환경 변수(APP_USER_PASSWORD 등)로만 씁니다. 화면·명령줄에 나오지 않습니다.
# 환경 변수: WAIT_SEC(처음 기다림, 기본 70), MAX_WAIT_SEC(배치 대기 한도, 기본 300)
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash가 컨테이너 안 경로를 윈도우 경로로 바꾸지 않게
cd "$(dirname "$0")/../.."

TAG="${1:-}"
if [[ ! "$TAG" =~ ^[a-z0-9]{1,8}$ ]]; then
  echo "사용법: $0 <실행 태그(영문 소문자·숫자)>"; exit 1
fi
WAIT_SEC="${WAIT_SEC:-70}"
MAX_WAIT_SEC="${MAX_WAIT_SEC:-300}"

sql() { # 표준 입력의 SQL을 앱 계정으로 실행
  docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
    bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"'
}
redis() { docker compose exec -T redis redis-cli "$@" 2>/dev/null | tr -d '\r'; }

# ── 1) 배치 대기 ────────────────────────────────────────────────
echo "위험 점수 배치를 기다립니다 (처음 ${WAIT_SEC}초, 그 뒤 risk:dirty가 빌 때까지 최대 ${MAX_WAIT_SEC}초)"
sleep "$WAIT_SEC"
waited=0
while :; do
  dirty=$(redis SCARD risk:dirty); work=$(redis EXISTS risk:dirty:work)
  [[ "$dirty" == "0" && "$work" == "0" ]] && break
  if (( waited >= MAX_WAIT_SEC )); then
    echo "경고: ${MAX_WAIT_SEC}초가 지나도 배치가 끝나지 않음 (risk:dirty ${dirty}건). 앱이 떠 있는지, jejari.risk.batch-enabled 인지 확인하세요."; break
  fi
  sleep 5; waited=$((waited + 5))
done

# ── 2) 정답 라벨 ─────────────────────────────────────────────────
LAB="SELECT member_id, REGEXP_SUBSTR(email, '^lt[a-z0-9]+\\.([a-z]+)\\.', 1, 1, NULL, 1) AS kind,
                TO_NUMBER(REGEXP_SUBSTR(email, '\\.g([0-9]+)\\.', 1, 1, NULL, 1)) AS grp
           FROM member WHERE email LIKE 'lt${TAG}.%'"

ids=$(sql <<EOF | tr -d ' \t\r' | { grep -E '^[0-9]+$' || true; } | paste -sd, -
SET HEADING OFF FEEDBACK OFF PAGESIZE 0
SELECT member_id FROM (${LAB}) WHERE kind IN ('normal','fast','retry','multi','cancel');
EOF
)
if [[ -z "$ids" ]]; then echo "태그 ${TAG}의 계정이 없습니다. 시나리오 12를 먼저 돌리세요."; exit 1; fi

sql <<EOF
SET FEEDBACK OFF DEFINE OFF
MERGE INTO synthetic_label t
USING (SELECT member_id,
              CASE kind WHEN 'normal' THEN 'NORMAL' WHEN 'multi' THEN 'RESELLER' WHEN 'cancel' THEN 'CANCEL_ABUSER' ELSE 'MACRO' END AS user_type,
              CASE kind WHEN 'multi' THEN grp + 1 END AS group_id,
              '{"source":"loadtest/12_bot_detection","run":"${TAG}","behavior":"' || kind || '"}' AS params
         FROM (${LAB}) WHERE kind IN ('normal','fast','retry','multi','cancel')) s
ON (t.member_id = s.member_id)
WHEN MATCHED THEN UPDATE SET t.user_type = s.user_type, t.group_id = s.group_id, t.generation_params = s.params, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN INSERT (member_id, user_type, group_id, generation_params) VALUES (s.member_id, s.user_type, s.group_id, s.params);
COMMIT;
EOF

# ── 3) 기기 ↔ 회원 (Mongo) ───────────────────────────────────────
pairs=$(docker compose exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "$0"' "
db.behavior_log.aggregate([
  { \$match: { member_id: { \$in: [${ids}] }, device_hash: { \$type: 'string' } } },
  { \$group: { _id: { m: '\$member_id', d: '\$device_hash' } } }
]).forEach(r => print(r._id.m + ',' + r._id.d))" | tr -d '\r' | grep -E '^[0-9]+,[0-9a-f]{64}$' || true)

dev="SELECT CAST(NULL AS NUMBER) member_id, CAST(NULL AS VARCHAR2(64)) device_hash FROM dual WHERE 1 = 0"
while IFS=, read -r m d; do
  [[ -n "$m" ]] && dev+=$'\n'"  UNION ALL SELECT ${m}, '${d}' FROM dual" # 한 줄씩 (sqlplus 한 줄 4999자 제한)
done <<< "$pairs"

# ── 4) 평가 ──────────────────────────────────────────────────────
sql <<EOF
SET FEEDBACK OFF DEFINE OFF LINESIZE 200 PAGESIZE 100
COLUMN kind FORMAT A8
COLUMN label FORMAT A14
COLUMN rule_code FORMAT A20
COLUMN subject FORMAT A8
WITH lab AS (${LAB}),
subj AS (
  SELECT l.member_id, 'CI' st, m.ci_hash sh FROM lab l JOIN member m ON m.member_id = l.member_id
  UNION ALL
  SELECT l.member_id, 'PAYMENT', p.method_hash FROM lab l JOIN payment_method p ON p.member_id = l.member_id WHERE p.method_hash IS NOT NULL
  UNION ALL
  SELECT member_id, 'DEVICE', device_hash FROM (${dev})
),
per AS (
  SELECT l.member_id, l.kind,
         CASE WHEN EXISTS (SELECT 1 FROM subj s JOIN suspicious_case c ON c.subject_type = s.st AND c.subject_hash = s.sh
                            WHERE s.member_id = l.member_id) THEN 1 ELSE 0 END AS det,
         (SELECT MAX(r.final_score) FROM subj s JOIN risk_score r ON r.subject_type = s.st AND r.subject_hash = s.sh
           WHERE s.member_id = l.member_id) AS score
    FROM lab l WHERE l.kind IN ('normal','fast','retry','multi','cancel')
)
SELECT p.kind, MAX(sl.user_type) label, COUNT(*) members, SUM(p.det) detected,
       ROUND(100 * SUM(p.det) / COUNT(*), 1) pct, ROUND(AVG(NVL(p.score, 0)), 1) avg_score, NVL(MAX(p.score), 0) max_score
  FROM per p JOIN synthetic_label sl ON sl.member_id = p.member_id
 GROUP BY p.kind ORDER BY DECODE(p.kind, 'normal', 0, 1), p.kind;

PROMPT
PROMPT 걸린 규칙 (종류별, 그 규칙의 케이스가 연결된 회원 수)
WITH lab AS (${LAB}),
subj AS (
  SELECT l.member_id, 'CI' st, m.ci_hash sh FROM lab l JOIN member m ON m.member_id = l.member_id
  UNION ALL
  SELECT l.member_id, 'PAYMENT', p.method_hash FROM lab l JOIN payment_method p ON p.member_id = l.member_id WHERE p.method_hash IS NOT NULL
  UNION ALL
  SELECT member_id, 'DEVICE', device_hash FROM (${dev})
)
SELECT l.kind, c.subject_type subject, NVL(cm.rule_code, c.trigger_type) rule_code, COUNT(DISTINCT l.member_id) members
  FROM lab l JOIN subj s ON s.member_id = l.member_id
  JOIN suspicious_case c ON c.subject_type = s.st AND c.subject_hash = s.sh
  LEFT JOIN case_marker cm ON cm.case_id = c.case_id
 WHERE l.kind IN ('normal','fast','retry','multi','cancel')
 GROUP BY l.kind, c.subject_type, NVL(cm.rule_code, c.trigger_type)
 ORDER BY l.kind, members DESC;

PROMPT
WITH lab AS (${LAB}),
subj AS (
  SELECT l.member_id, 'CI' st, m.ci_hash sh FROM lab l JOIN member m ON m.member_id = l.member_id
  UNION ALL
  SELECT l.member_id, 'PAYMENT', p.method_hash FROM lab l JOIN payment_method p ON p.member_id = l.member_id WHERE p.method_hash IS NOT NULL
  UNION ALL
  SELECT member_id, 'DEVICE', device_hash FROM (${dev})
),
per AS (
  SELECT l.kind, CASE WHEN EXISTS (SELECT 1 FROM subj s JOIN suspicious_case c ON c.subject_type = s.st AND c.subject_hash = s.sh
                                    WHERE s.member_id = l.member_id) THEN 1 ELSE 0 END AS det
    FROM lab l WHERE l.kind IN ('normal','fast','retry','multi','cancel')
)
SELECT '탐지율(봇) ' || NVL(ROUND(100 * SUM(CASE WHEN kind <> 'normal' THEN det END) / NULLIF(SUM(CASE WHEN kind <> 'normal' THEN 1 END), 0), 1), 0)
       || '% (목표 90% 이상) · 오탐률(정상) ' || NVL(ROUND(100 * SUM(CASE WHEN kind = 'normal' THEN det END) / NULLIF(SUM(CASE WHEN kind = 'normal' THEN 1 END), 0), 1), 0)
       || '% (목표 5% 이하)' AS result
  FROM per;
EOF
