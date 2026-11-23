// 첫 기동 때 한 번 실행됩니다 (/docker-entrypoint-initdb.d). 데이터베이스 jejari.
// 인덱스·보관: DB설계서 6.1, 보관 방식: concurrency.md §13.2 (expire_at + TTL expireAfterSeconds: 0)
// 이미 만든 볼륨에는 다시 돌지 않으니, 인덱스를 추가하면 mongosh로 이 파일을 직접 실행합니다 (createIndex는 여러 번 돌려도 안전).

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
  const coll = jejari.getCollection(name);
  for (const [key, opts] of indexes) coll.createIndex(key, opts);
}

// 행동 로그 (ts + 180일). 앱 → Kafka jejari.behavior → insertMany
ensure('behavior_log', [
  [{ ci_hash: 1, ts: -1 }, { name: 'ix_ci_ts' }],
  // { run_id: 1, ts: 1 } (ix_run_ts)는 ERD v33에서 뺌: 쓰는 곳이 운행별 정합성 점검뿐이고 그것도 _id 범위로 3ms, 빼면 쓰기 +12~18%
  [{ member_id: 1, ts: 1 }, { name: 'ix_member_ts' }], // 타임라인 ADM-05
  [{ device_hash: 1, ts: 1 }, { name: 'ix_device_ts' }], // 기기 단위 위험 점수 집계 (ERD v19)
  // Kafka 재전달 중복 방지 (ERD v16). event_id가 있는 문서만 유니크 — 없는 옛 문서끼리는 충돌하지 않게
  [{ event_id: 1 }, { name: 'ux_event_id', unique: true, partialFilterExpression: { event_id: { $type: 'string' } } }],
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

// 화면 녹화 (ADM-11, 세션 시작 + replay.retention_days 90일, 케이스에 걸리면 legal_hold로 expire_at 제거)
// 시계열 컬렉션이 아니라 일반 컬렉션이다 — 시계열은 유니크 인덱스를 못 만들어 재전달 중복을 막을 수 없다 (ERD v28)
ensure('replay_session', [
  [{ ci_hash: 1, started_at: 1 }, { name: 'ix_ci_started' }],
  [{ device_hash: 1, started_at: 1 }, { name: 'ix_device_started' }],
  [{ member_id: 1, started_at: 1 }, { name: 'ix_member_started' }],
  [{ expire_at: 1 }, Object.assign({ name: 'ttl_expire_at' }, TTL)],
]);
ensure('replay_event', [
  [{ session_id: 1, seq: 1 }, { name: 'ux_session_seq', unique: true }], // 재전달 중복 방지 + 한 세션 재생 조회
  [{ expire_at: 1 }, Object.assign({ name: 'ttl_expire_at' }, TTL)],
]);

// 모델 보조 의견 (ERD v25, RISK-05). 오프라인 Python이 적재, 같은 버전 재적재는 upsert. TTL 없음 (발표 수치 근거)
ensure('model_score', [
  [{ member_id: 1, model_version: 1 }, { name: 'ux_member_version', unique: true }],
  [{ model_version: 1, score: -1 }, { name: 'ix_version_score' }], // A-05 목록 (높은 점수 순)
]);

print('jejari mongo init done');
