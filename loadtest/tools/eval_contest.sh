#!/usr/bin/env bash
# 봇 대회 평가 (docs/강동균/봇-대회-제안.md): 봇마다 "표를 얼마나 가져갔고, 어느 방어층에서 막혔나"
#
# 사용법 (저장소 어디서든, docker compose가 떠 있는 서버에서):
#   loadtest/tools/eval_contest.sh <실행 태그> <대회 운행 ID>
#
# 라벨은 이메일에서 읽는다: lt{태그}.{종류}.g{묶음}.{번호}@…
#   종류 = bota · botb · botc · botd (봇) · human (사람 참가자)
#
# 방어층 (봇 하나가 여러 층에 걸릴 수 있다):
#   1. 매수 제한   좌석 선점이 422 LIMIT_* 로 거절된 횟수 (Mongo 행동 로그 meta.errorCode)
#   2. 규칙 점수   연결된 대상(CI · 결제 수단 · 기기)에 의심 케이스가 열림 = "탐지"
#   3. 순번 조정   위험 점수 × risk.penalty_ms_per_point 만큼 대기열에서 뒤로 (다음 판매부터 영향)
#   4. 관리자 판정 케이스 판정이 끝났는지 (CONFIRMED · DISMISSED)
#   층 하나도 안 걸리고 표를 가져갔으면 "뚫림"
#
# 사람(human)은 탐지되면 오탐이다. 목표: 오탐률 5% 이하.
# 비밀값은 컨테이너 안 환경 변수로만 쓴다 (eval_risk.sh와 같은 방식).
# 환경 변수: WAIT_SEC(처음 기다림, 기본 70), MAX_WAIT_SEC(배치 대기 한도, 기본 300)
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../.."

TAG="${1:-}"; RUN="${2:-}"
if [[ ! "$TAG" =~ ^[a-z0-9]{1,8}$ || ! "$RUN" =~ ^[0-9]+$ ]]; then
  echo "사용법: $0 <실행 태그(영문 소문자·숫자)> <대회 운행 ID>"; exit 1
fi
KINDS="'bota','botb','botc','botd','human'"
WAIT_SEC="${WAIT_SEC:-70}"
MAX_WAIT_SEC="${MAX_WAIT_SEC:-300}"

sql() {
  docker compose exec -T -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 oracle \
    bash -c 'sqlplus -S -L "$APP_USER/$APP_USER_PASSWORD@localhost:1521/FREEPDB1"'
}
redis() { docker compose exec -T redis redis-cli "$@" 2>/dev/null | tr -d '\r'; }
mongo() {
  docker compose exec -T mongo bash -c \
    'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "$0"' "$1" \
    | tr -d '\r'
}

# ── 1) 위험 점수 배치 대기 (eval_risk.sh와 같음) ─────────────────
echo "위험 점수 배치를 기다립니다 (처음 ${WAIT_SEC}초, 그 뒤 risk:dirty가 빌 때까지 최대 ${MAX_WAIT_SEC}초)"
sleep "$WAIT_SEC"
waited=0
while :; do
  dirty=$(redis SCARD risk:dirty); work=$(redis EXISTS risk:dirty:work)
  [[ "$dirty" == "0" && "$work" == "0" ]] && break
  if (( waited >= MAX_WAIT_SEC )); then echo "경고: 배치가 끝나지 않음 (risk:dirty ${dirty}건)"; break; fi
  sleep 5; waited=$((waited + 5))
done

# ── 2) 참가자 ────────────────────────────────────────────────────
LAB="SELECT member_id, REGEXP_SUBSTR(email, '^lt[a-z0-9]+\\.([a-z]+)\\.', 1, 1, NULL, 1) AS kind,
                TO_NUMBER(REGEXP_SUBSTR(email, '\\.g([0-9]+)\\.', 1, 1, NULL, 1)) AS grp
           FROM member WHERE email LIKE 'lt${TAG}.%'"
ids=$(sql <<EOF | tr -d ' \t\r' | { grep -E '^[0-9]+$' || true; } | paste -sd, -
SET HEADING OFF FEEDBACK OFF PAGESIZE 0
SELECT member_id FROM (${LAB}) WHERE kind IN (${KINDS});
EOF
)
if [[ -z "$ids" ]]; then echo "태그 ${TAG}의 참가자 계정이 없습니다."; exit 1; fi

# 정답 라벨: 봇은 모두 "표를 쓸어 담으려는" 공격이라 RESELLER, 사람은 NORMAL
sql <<EOF
SET FEEDBACK OFF DEFINE OFF
MERGE INTO synthetic_label t
USING (SELECT member_id, CASE kind WHEN 'human' THEN 'NORMAL' ELSE 'RESELLER' END AS user_type,
              CASE WHEN kind <> 'human' THEN grp + 1 END AS group_id,
              '{"source":"loadtest/contest","run":"${TAG}","behavior":"' || kind || '"}' AS params
         FROM (${LAB}) WHERE kind IN (${KINDS})) s
ON (t.member_id = s.member_id)
WHEN MATCHED THEN UPDATE SET t.user_type = s.user_type, t.group_id = s.group_id, t.generation_params = s.params, t.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN INSERT (member_id, user_type, group_id, generation_params) VALUES (s.member_id, s.user_type, s.group_id, s.params);
COMMIT;
EOF

# ── 3) Mongo: 기기 연결 · 매수 제한 거절 ─────────────────────────
pairs=$(mongo "
db.behavior_log.aggregate([
  { \$match: { member_id: { \$in: [${ids}] }, device_hash: { \$type: 'string' } } },
  { \$group: { _id: { m: '\$member_id', d: '\$device_hash' } } }
]).forEach(r => print(r._id.m + ',' + r._id.d))" | grep -E '^[0-9]+,[0-9a-f]{64}$' || true)
limits=$(mongo "
db.behavior_log.aggregate([
  { \$match: { member_id: { \$in: [${ids}] }, run_id: ${RUN}, event_type: 'SEAT_HOLD', 'meta.errorCode': /^LIMIT_/ } },
  { \$group: { _id: '\$member_id', n: { \$sum: 1 } } }
]).forEach(r => print(r._id + ',' + r.n))" | grep -E '^[0-9]+,[0-9]+$' || true)
holds=$(mongo "
db.behavior_log.aggregate([
  { \$match: { member_id: { \$in: [${ids}] }, run_id: ${RUN}, event_type: 'SEAT_HOLD' } },
  { \$group: { _id: '\$member_id', n: { \$sum: 1 } } }
]).forEach(r => print(r._id + ',' + r.n))" | grep -E '^[0-9]+,[0-9]+$' || true)

rows() { # "m,v" 줄들 → SELECT m, v FROM dual UNION ALL … (한 줄씩: sqlplus 한 줄 4999자 제한)
  local out="SELECT CAST(NULL AS NUMBER) member_id, CAST(NULL AS $2) v FROM dual WHERE 1 = 0" m v
  while IFS=, read -r m v; do
    [[ -n "$m" ]] && out+=$'\n'"  UNION ALL SELECT ${m}, $( [[ "$2" == NUMBER ]] && echo "$v" || echo "'$v'" ) FROM dual"
  done <<< "$1"
  printf '%s' "$out"
}
DEV=$(rows "$pairs" "VARCHAR2(64)")
LIM=$(rows "$limits" NUMBER)
HOLD=$(rows "$holds" NUMBER)

# ── 4) 평가 ──────────────────────────────────────────────────────
sql <<EOF
SET FEEDBACK OFF DEFINE OFF LINESIZE 220 PAGESIZE 100
COLUMN kind FORMAT A6
COLUMN layers FORMAT A44
COLUMN rule_code FORMAT A20
COLUMN subject FORMAT A8
WITH lab AS (${LAB}),
subj AS (
  SELECT l.member_id, 'CI' st, m.ci_hash sh FROM lab l JOIN member m ON m.member_id = l.member_id
  UNION ALL
  SELECT l.member_id, 'PAYMENT', p.method_hash FROM lab l JOIN payment_method p ON p.member_id = l.member_id WHERE p.method_hash IS NOT NULL
  UNION ALL
  SELECT member_id, 'DEVICE', v FROM (${DEV})
),
pen AS (SELECT TO_NUMBER(policy_value) ms FROM app_policy WHERE policy_key = 'risk.penalty_ms_per_point'),
per AS (
  SELECT l.member_id, l.kind, m.ci_hash,
         (SELECT COUNT(*) FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.reservation_id
           WHERE r.member_id = l.member_id AND r.run_id = ${RUN} AND r.status = 'CONFIRMED') AS paid,
         (SELECT COUNT(*) FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.reservation_id
           WHERE r.member_id = l.member_id AND r.run_id = ${RUN} AND r.status IN ('HOLD', 'PAYMENT_PENDING')) AS held,
         NVL((SELECT SUM(v) FROM (${LIM}) x WHERE x.member_id = l.member_id), 0) AS lim,
         NVL((SELECT SUM(v) FROM (${HOLD}) x WHERE x.member_id = l.member_id), 0) AS tries,
         CASE WHEN EXISTS (SELECT 1 FROM subj s JOIN suspicious_case c ON c.subject_type = s.st AND c.subject_hash = s.sh
                            WHERE s.member_id = l.member_id) THEN 1 ELSE 0 END AS det,
         CASE WHEN EXISTS (SELECT 1 FROM subj s JOIN suspicious_case c ON c.subject_type = s.st AND c.subject_hash = s.sh
                            WHERE s.member_id = l.member_id AND c.status IN ('CONFIRMED', 'DISMISSED')) THEN 1 ELSE 0 END AS judged,
         NVL((SELECT MAX(r.final_score) FROM subj s JOIN risk_score r ON r.subject_type = s.st AND r.subject_hash = s.sh
               WHERE s.member_id = l.member_id), 0) AS score
    FROM lab l JOIN member m ON m.member_id = l.member_id
   WHERE l.kind IN (${KINDS})
),
agg AS (
  SELECT kind, COUNT(*) members, COUNT(DISTINCT ci_hash) ids, SUM(paid) paid, SUM(held) held, SUM(tries) tries,
         SUM(lim) lim, SUM(det) det, SUM(judged) judged, MAX(score) max_score,
         ROUND(MAX(score) * (SELECT ms FROM pen) / 1000) pen_sec
    FROM per GROUP BY kind
)
SELECT kind, members, ids "명의", paid "결제한 좌석", held "잡고 안 낸 좌석", tries "선점 시도",
       lim "제한 거절", det "탐지 계정", max_score "최고 점수", pen_sec "순번 -초",
       CASE WHEN kind = 'human' THEN CASE WHEN det > 0 THEN '오탐 ' || det || '명' ELSE '오탐 없음' END
            ELSE NVL(RTRIM(CASE WHEN lim > 0 THEN '매수제한 · ' END
                          || CASE WHEN det > 0 THEN '규칙(케이스) · ' END
                          || CASE WHEN pen_sec > 0 THEN '순번조정 · ' END
                          || CASE WHEN judged > 0 THEN '관리자판정 · ' END, ' · '), '뚫림') END AS layers
  FROM agg ORDER BY DECODE(kind, 'human', 1, 0), kind;

PROMPT
PROMPT 걸린 규칙 (종류별, 그 규칙의 케이스가 연결된 계정 수)
WITH lab AS (${LAB}),
subj AS (
  SELECT l.member_id, 'CI' st, m.ci_hash sh FROM lab l JOIN member m ON m.member_id = l.member_id
  UNION ALL
  SELECT l.member_id, 'PAYMENT', p.method_hash FROM lab l JOIN payment_method p ON p.member_id = l.member_id WHERE p.method_hash IS NOT NULL
  UNION ALL
  SELECT member_id, 'DEVICE', v FROM (${DEV})
)
SELECT l.kind, c.subject_type subject, NVL(cm.rule_code, c.trigger_type) rule_code, COUNT(DISTINCT l.member_id) members
  FROM lab l JOIN subj s ON s.member_id = l.member_id
  JOIN suspicious_case c ON c.subject_type = s.st AND c.subject_hash = s.sh
  LEFT JOIN case_marker cm ON cm.case_id = c.case_id
 WHERE l.kind IN (${KINDS})
 GROUP BY l.kind, c.subject_type, NVL(cm.rule_code, c.trigger_type)
 ORDER BY l.kind, members DESC;

PROMPT
PROMPT 읽는 법: "명의" = 서로 다른 본인인증(CI) 수 (대회 규칙: 봇당 최대 6). 명의당 열차 4매가 매수 제한의 바닥.
PROMPT          "순번 -초" = 다음 판매에서 이 봇이 대기열에서 밀리는 시간. 탐지는 케이스 기준이라 점수만 오른 건 탐지가 아니다.
EOF
