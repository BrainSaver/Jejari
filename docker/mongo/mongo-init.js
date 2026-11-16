// 첫 기동 때 한 번 실행됩니다 (/docker-entrypoint-initdb.d). 데이터베이스 jejari.
// 인덱스·보관: DB설계서 6.1, 보관 방식: concurrency.md §13.2 (expire_at + TTL expireAfterSeconds: 0)
// 이미 만든 볼륨에는 다시 돌지 않으니, 인덱스를 추가하면 mongosh로 이 파일을 직접 실행합니다 (createIndexes는 여러 번 돌려도 안전).

const jejari = db.getSiblingDB('jejari');

// 앱 계정: jejari 데이터베이스 읽기·쓰기만
const appUser = process.env.MONGO_APP_USER;
if (appUser && !jejari.getUser(appUser)) {
  jejari.createUser({
    user: appUser,
    pwd: process.env.MONGO_APP_PASSWORD,
    roles: [{ role: 'readWrite', db: 'jejari' }],
  });
}

const TTL = { expireAfterSeconds: 0 };

function ensure(name, indexes) {
  if (!jejari.getCollectionNames().includes(name)) jejari.createCollection(name);
  jejari.getCollection(name).createIndexes(
    indexes.map(([key, opts]) => Object.assign({ key }, opts)),
  );
}

// 행동 로그 (ts + 180일). 앱 → Kafka jejari.behavior → insertMany
ensure('behavior_log', [
  [{ ci_hash: 1, ts: -1 }, { name: 'ix_ci_ts' }],
  [{ run_id: 1, ts: 1 }, { name: 'ix_run_ts' }],
  [{ member_id: 1, ts: 1 }, { name: 'ix_member_ts' }], // 타임라인 ADM-05
  [{ expire_at: 1 }, Object.assign({ name: 'ttl_expire_at' }, TTL)],
]);

// 대기열 로그 (entered_at + 30일)
ensure('queue_log', [
  [{ run_id: 1, entered_at: 1 }, { name: 'ix_run_entered' }], // 순번 검증
  [{ expire_at: 1 }, Object.assign({ name: 'ttl_expire_at' }, TTL)],
]);

// 로그인 시도 (ts + 30일)
ensure('login_attempt', [
  [{ ip_hash: 1, ts: -1 }, { name: 'ix_ip_ts' }],
  [{ email_hash: 1, ts: -1 }, { name: 'ix_email_ts' }],
  [{ expire_at: 1 }, Object.assign({ name: 'ttl_expire_at' }, TTL)],
]);

print('jejari mongo init done');
