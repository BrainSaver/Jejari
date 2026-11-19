-- ERD v27 → v28 델타.
--
-- v28은 MongoDB 컬렉션(replay_event · replay_session)만 바꿉니다. **Oracle은 바뀌는 것이 없습니다.**
-- 이 파일은 "v28을 확인했다"는 자리만 지킵니다. 실행할 필요도, 실행해도 바뀌는 것도 없습니다.
--
-- Mongo 쪽 반영은 docker/mongo/mongo-init.js가 합니다 (컨테이너를 처음 띄울 때 한 번).
-- 이미 돌아가는 Mongo에 인덱스를 맞추려면 아래를 한 번 실행하세요. 다시 실행해도 안전합니다
-- (ReplaySink가 뜰 때도 같은 인덱스를 보장하므로, 앱을 한 번 재시작하는 것으로도 됩니다).
--
--   docker compose exec -T mongo mongosh --quiet \
--     -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari <<'JS'
--   db.replay_event.createIndex({ session_id: 1, seq: 1 }, { name: 'ux_session_seq', unique: true });
--   db.replay_event.createIndex({ expire_at: 1 }, { name: 'ttl_expire_at', expireAfterSeconds: 0 });
--   db.replay_session.createIndex({ expire_at: 1 }, { name: 'ttl_expire_at', expireAfterSeconds: 0 });
--   JS
--
-- 왜 바꿨나: replay_event를 시계열 컬렉션으로 적어 뒀는데, MongoDB 시계열 컬렉션은 유니크 인덱스를
-- 만들 수 없습니다. DB설계서는 { session_id, seq } 유니크를 요구합니다 (Kafka 재전달·네트워크
-- 재전송이 같은 이벤트를 두 번 저장하지 않게). 둘 중 유니크가 더 중요해 일반 컬렉션으로 맞췄고,
-- 쓰지 않는 칸 두 개를 지우고 실제로 저장하는 칸 세 개(expire_at · label · boxes)를 넣었습니다.
-- 구현은 처음부터 이대로였고(강다인 님, ADM-11), ERD만 뒤처져 있었습니다.

SET DEFINE OFF

PROMPT v28: Oracle 변경 없음 (MongoDB replay_event·replay_session만 바뀜)
