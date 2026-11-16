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

"$TOPICS" --bootstrap-server "$BOOTSTRAP" --list
