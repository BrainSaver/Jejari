# 서버 요청 · 행동 로그 색인 `ix_run_ts` 지우기

> 요청: 강동균 · 2026-11-23 · 받는 사람: 공용 서버 담당 · **급하지 않음** (지금도 정상 동작, 쓰기를 가볍게 하는 최적화)
> 서버에서 할 일은 **명령 1줄 + 확인 1줄**, 5분이면 끝납니다.

## 한 줄 요약

MongoDB 행동 로그(`behavior_log`)의 색인 7개 중 **`ix_run_ts` 하나를 지우면 행동 로그 쓰기가 12~18% 빨라집니다.**
이 색인을 쓰는 곳은 운행별 정합성 점검 하나뿐이고, 그 점검은 다른 방법으로 3ms 안에 끝나도록 이미 고쳐 두었습니다.

## 이게 뭔가요

- 색인(index)은 책 뒤의 "찾아보기"입니다. `ix_run_ts`는 "몇 번 운행의, 언제 행동 로그인가"(`run_id`, `ts`)로 빨리 찾는 찾아보기입니다.
- 찾아보기는 공짜가 아닙니다. **행동 로그가 한 건 저장될 때마다 색인 7개를 모두 함께 고쳐 씁니다.** 행동 로그는 이 프로젝트에서 가장 많이 쌓이는 데이터(요청마다 1건, 180일 보관)라 이 비용이 큽니다.

## 왜 지우나요 (실측, 2026-11-23 로컬)

| | 내용 |
|---|---|
| 이 색인을 쓰는 곳 | `loadtest/tools/check_booking.sh <운행번호>` (운행 하나만 보는 정합성 점검) **하나뿐**. 앱 기능(위험 점수·케이스·녹화 재생·모델)과 매시간 서버 점검(`all` 모드)은 쓰지 않음 |
| 지우면 얻는 것 | 행동 로그 50만 건 넣기: 초당 25,531~28,542 → **30,043~31,959건 (+12~18%)**. 디스크 200만 건당 약 46MB |
| 지우면 잃는 것 | 운행별 점검 1ms → 3ms (저장 시각 `_id`로 먼저 좁히도록 11-23에 고침) |
| 왜 중요한가 | 판매 오픈처럼 로그가 쏟아질 때, 장애 뒤 밀린 로그를 따라잡을 때 DB·CPU 부담이 줄어 예매가 덜 느려짐 |

측정 방법: 운영과 같은 색인을 단 200만 건 시험 컬렉션에서 코드의 조회 모양을 모두 `explain`으로 확인하고, 색인 구성만 바꿔 50만 건씩 2회 넣기. 자세한 표는 [행동-로그-적재-용량](행동-로그-적재-용량.md) "인덱스 정리 측정".

## 안전한가요

- **앱은 이 색인을 다시 만들지 않습니다.** 앱이 기동 때 확인하는 색인은 `ux_event_id`·`ttl_expire_at`·`ix_device_ts`뿐. `docker/mongo/mongo-init.js`는 DB 볼륨을 처음 만들 때만 돕니다
- **서비스 중에 지워도 됩니다.** 색인 삭제는 순간적이고 읽기·쓰기를 멈추지 않습니다
- **되돌리기도 명령 1줄입니다** (아래). 다시 만드는 동안에도 서비스는 그대로 돌아갑니다 (데이터 양에 따라 몇 초~몇 분)

## 서버에서 할 일

앱을 설치한 계정(`dita_810`)으로 바꿔서 합니다. 다른 계정에는 `~/Jejari`가 없습니다.

```bash
sudo -iu dita_810
```

```bash
cd ~/Jejari && git log --oneline -1
```
→ 서버 코드가 `c52aca6`(11-23) 이후인지 확인 (운행별 점검이 `_id`로 좁히는 버전). 아니면 자동 배포가 끝난 뒤에 합니다.

**1) 지우기 전 색인 목록 보기**
```bash
docker compose -f compose.yaml -f deploy/compose.server.yaml exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "db.behavior_log.getIndexes().map(i => i.name)"'
```
→ `ix_run_ts`가 목록에 있어야 합니다.

**2) 지우기**
```bash
docker compose -f compose.yaml -f deploy/compose.server.yaml exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "db.behavior_log.dropIndex(\"ix_run_ts\")"'
```
→ `{ nIndexesWas: 7, ok: 1 }` 비슷하게 나오면 성공.

**3) 확인** — 1)을 다시 실행해 `ix_run_ts`가 없으면 끝. 이어서 정합성 점검을 한 번 돌려 OK인지 봅니다.
```bash
sudo systemctl start jejari-integrity && journalctl -u jejari-integrity -n 15 --no-pager
```

### 되돌리기 (문제가 생기면)
```bash
docker compose -f compose.yaml -f deploy/compose.server.yaml exec -T mongo bash -c 'mongosh --quiet -u "$MONGO_APP_USER" -p "$MONGO_APP_PASSWORD" --authenticationDatabase jejari jejari --eval "db.behavior_log.createIndex({ run_id: 1, ts: 1 }, { name: \"ix_run_ts\" })"'
```

## 저장소 쪽 — ✅ ERD v33에 반영 (2026-11-23)

새로 만드는 DB에도 이 색인이 생기지 않게 두 파일을 고쳤습니다 (비회원 예매 v33과 같은 커밋). 자동 배포를 멈추는 파일이라 **A-08 "막힌 커밋 승인"** 한 번이 필요하고, 서버 색인은 위 명령으로 따로 지웁니다.

- ERD(`docs/erd/jejari-erd.html`) 새 패치: `behavior_log` 비고에서 `{ run_id: 1, ts: 1 }` 빼기 (ERD 담당)
- `docker/mongo/mongo-init.js`: `ix_run_ts` 줄 빼기

순서는 상관없습니다. 서버에서 먼저 지워도 앱·점검은 그대로 돌아갑니다.

## 문의

- 측정 근거: [행동-로그-적재-용량](행동-로그-적재-용량.md), 개발일지 [11-23](../devlog/20261123-인덱스-정리-측정과-로컬-k6.md)
- 안 되거나 출력이 다르면 그 화면을 강동균에게 보내 주세요
