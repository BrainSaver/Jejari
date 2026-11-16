# [infra] mongo-init.js 인덱스 생성 실패로 Mongo 컨테이너 종료

| 항목 | 내용 |
|---|---|
| 작성자 | 조영준 |
| 날짜 | 2026-11-16 |
| 상태 | 해결 |
| 관련 커밋·이슈 | (커밋 후 기입) |

## 1. 문제

- 현상: `docker compose up -d` 직후 mongo 컨테이너가 `Exited (1)`.
  ```
  MongoServerError: Error in specification { name: "key_[object Object]_name_ix_ci_ts", key: { key: { ci_hash: 1, ts: -1 }, name: "ix_ci_ts" } } :: caused by :: Unknown index plugin 'ix_ci_ts'
  ```
- 재현 조건: 빈 볼륨으로 첫 기동 (`/docker-entrypoint-initdb.d/mongo-init.js` 실행 시)
- 영향: Mongo가 뜨지 않아 behavior_log·queue_log·login_attempt 적재 불가. 초기화가 중간에 끊긴 볼륨은 다시 띄워도 init이 돌지 않음

## 2. 원인

- 오류 메시지의 `key: { key: {...}, name: ... }` → 인덱스 명세 객체 전체가 "키 패턴"으로 들어감
- mongosh의 `collection.createIndexes(keyPatterns, options)`는 **키 패턴 배열**을 받습니다.
  `{ key, name }` 형태의 명세 배열을 받는 것은 서버 명령 `db.runCommand({ createIndexes, indexes })`입니다. 두 API를 섞어 씀
- `name: "ix_ci_ts"`가 키 패턴 안의 값으로 해석돼 "ix_ci_ts라는 인덱스 종류(plugin)"를 찾다가 실패

## 3. 해결

| 선택지 | 장점 | 단점 | 채택 |
|---|---|---|---|
| `db.runCommand({ createIndexes, indexes: [...] })` | 한 번에 생성 | 코드가 길어짐 | |
| 인덱스마다 `createIndex(key, options)` | 가장 단순, 다시 실행해도 안전(같은 인덱스면 무시) | 호출 여러 번 | ✓ |

- 바뀐 코드 위치: `docker/mongo/mongo-init.js`
- 실패한 볼륨은 init이 다시 돌지 않으므로 `docker compose rm -sf mongo && docker volume rm jejari_mongo-data` 후 재기동

## 4. 확인

- 앱 계정으로 접속해 인덱스 확인: behavior_log(ix_ci_ts, ix_run_ts, ix_member_ts, TTL), queue_log(ix_run_entered, TTL), login_attempt(ix_ip_ts, ix_email_ts, TTL) 모두 생성, 쓰기·삭제 성공

## 5. 정리

- 한 줄 요약: "mongosh의 createIndexes가 인덱스 명세가 아닌 키 패턴을 받는다는 점을 놓쳐 첫 기동이 실패했고, createIndex 단건 호출로 바꿔 해결했다. 초기화 스크립트는 빈 볼륨에서만 돌기 때문에 실패 후에는 볼륨을 지워야 한다."
- 다음에 비슷한 상황이면: 초기화 스크립트는 커밋 전에 실제로 `docker compose up`으로 한 번 돌려 본다.
