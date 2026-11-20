#!/bin/bash
# 토픽 생성 (기능설계서 8.1). 파티션 수는 [가정] 값이라 바뀌면 여기만 고칩니다.
# 키: reservation=reservation_id, waitlist=waitlist_id, behavior=ci_hash, replay=session_id
set -euo pipefail

BOOTSTRAP=kafka:29092
TOPICS=/opt/kafka/bin/kafka-topics.sh

create() {
  "$TOPICS" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1
}

create jejari.reservation 12
create jejari.waitlist 6
create jejari.behavior 12
create jejari.replay 6   # P2

# 격리 보관함 (소비자가 포기·건너뛴 메시지, event/dlq/DeadLetters). 30일 보관 — 원인을 고친 뒤 tools/dlq.sh로 다시 넣는다.
# 앱도 뜰 때 없으면 만든다 (DeadLetterConfig)
for t in jejari.reservation.DLT jejari.behavior.DLT jejari.replay.DLT; do
  "$TOPICS" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$t" --partitions 1 --replication-factor 1 \
    --config retention.ms=2592000000
done

"$TOPICS" --bootstrap-server "$BOOTSTRAP" --list
