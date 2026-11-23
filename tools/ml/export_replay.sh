#!/usr/bin/env bash
# 화면 녹화(MongoDB replay_session·replay_event)를 꺼내 녹화 모델 입력(replay.jsonl)을 만든다.
# export_live.sh로 이미 꺼낸 폴더에 붙여 쓴다 (그 폴더의 members.jsonl에 있는 계정만).
#
#   tools/ml/export_live.sh <태그> data/ml/<폴더>      # 먼저 (회원·행동 로그·라벨)
#   tools/ml/export_replay.sh data/ml/<폴더>           # → data/ml/<폴더>/replay.jsonl
#   python3 -I tools/ml/replay_features.py data/ml/<폴더>
#
# 한 줄 = 녹화 이벤트 하나: member_id, session_id, seq, ts(ms), type, screen, target, x, y, pointer, path
# LAYOUT(화면 뼈대)은 크고 모델이 쓰지 않아 뺀다. 입력값은 원래 저장하지 않는다(길이만).
# 비밀값은 컨테이너 안 환경 변수로만 쓴다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../.."
OUT="${1:-}"
[[ -n "$OUT" && -f "$OUT/members.jsonl" ]] || { echo "사용법: $0 <export_live.sh로 꺼낸 폴더>"; exit 1; }

ids=$(python3 -I -c 'import json,sys; print(",".join(str(json.loads(l)["member_id"]) for l in open(sys.argv[1], encoding="utf-8")))' "$OUT/members.jsonl")
[[ -n "$ids" ]] || { echo "계정이 없습니다"; exit 1; }

docker compose exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "$0"' "
const ids = [${ids}].map((n) => NumberLong(n));
const owner = {};
db.replay_session.find({ member_id: { \$in: ids } }, { member_id: 1 }).forEach((s) => { owner[s._id] = Number(s.member_id); });
const sids = Object.keys(owner);
db.replay_event.find({ session_id: { \$in: sids }, type: { \$ne: 'LAYOUT' } },
    { session_id: 1, seq: 1, ts: 1, type: 1, screen: 1, target: 1, x: 1, y: 1, pointer: 1, path: 1 })
  .sort({ session_id: 1, seq: 1 })
  .forEach((e) => print(JSON.stringify({ member_id: owner[e.session_id], session_id: e.session_id, seq: Number(e.seq),
    ts: e.ts.getTime(), type: e.type, screen: e.screen, target: e.target, x: e.x, y: e.y, pointer: e.pointer,
    path: e.path ? e.path.map((p) => p.map(Number)) : undefined })));
" | tr -d '\r' | grep '^{' > "$OUT/replay.jsonl" || true

echo "녹화 이벤트 $(wc -l < "$OUT/replay.jsonl")건 → $OUT/replay.jsonl"
echo "다음: python3 -I tools/ml/replay_features.py $OUT"
